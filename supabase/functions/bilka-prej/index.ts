import { discoverProducts, discoveryStatus, DiscoveryError } from "./discovery_server.ts";
import { catalog, saveHouseholdDefault } from "./catalog_server.ts";
import { manualProductSearch } from "./manual_search_server.ts";
import { shoppingContext, recordPurchase, CheckoutError } from "./shopping_server.ts";
import { ALLOWED_BILKA_RPCS } from "./rpc_allowlist.ts";
import { priceOnlyUpdates } from "./price_updates.ts";
import {
  batchIds,
  dailyRemaining,
  toBilkaCacheRow,
  validPrejKey,
  type PrejProduct,
} from "./prej.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const PREJ_BASE = "https://api.prej.app";
const DAILY_BUDGET = 20; // 25 free calls; keep 5 in reserve (including key checks).

type Payload = {
  action?: "status" | "set-key" | "verify-key" | "remove-key" | "refresh" | "discover" | "discovery-status" | "catalog" | "choose-product" | "manual-search" | "nightly-status" | "set-nightly" | "shopping-context" | "record-purchase";
  apiKey?: unknown;
  householdId?: unknown;
  ingredientId?: unknown;
  ingredientIds?: unknown;
  productId?: unknown;
  query?: unknown;
  enabled?: unknown;
  weekId?: unknown;
  manualProductName?: unknown;
  packageCount?: unknown;
  amountBase?: unknown;
  paidTotalOre?: unknown;
};

class ClientError extends Error {
  constructor(public code: string, public status: number) {
    super(code);
  }
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
      "Access-Control-Allow-Methods": "POST, OPTIONS",
      "Cache-Control": "no-store",
    },
  });
}

async function adminRequest(path: string, method: "GET" | "POST" | "PATCH", body?: unknown, prefer?: string): Promise<unknown> {
  const response = await fetch(SUPABASE_URL + path, {
    method,
    headers: {
      apikey: SERVICE_KEY,
      Authorization: "Bearer " + SERVICE_KEY,
      "Content-Type": "application/json",
      Prefer: prefer ?? "return=representation,resolution=merge-duplicates",
    },
    body: body == null ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(15_000),
  });
  if (!response.ok) {
    // Never propagate SQL errors: they may include function arguments (API secrets).
    throw new ClientError("database_unavailable", 503);
  }
  const txt = await response.text();
  return txt.length ? JSON.parse(txt) : null;
}

async function rpc(name: string, args: Record<string, unknown>): Promise<unknown> {
  if (!ALLOWED_BILKA_RPCS.has(name)) throw new Error("Unknown RPC");
  return await adminRequest("/rest/v1/rpc/" + name, "POST", args);
}

async function currentUser(req: Request): Promise<string> {
  const auth = req.headers.get("Authorization") ?? "";
  if (!/^Bearer \S+$/.test(auth)) throw new ClientError("not_authenticated", 401);
  const response = await fetch(SUPABASE_URL + "/auth/v1/user", {
    headers: { apikey: SERVICE_KEY, Authorization: auth },
    signal: AbortSignal.timeout(10_000),
  });
  if (!response.ok) throw new ClientError("not_authenticated", 401);
  const user = await response.json();
  if (!user?.id || typeof user.id !== "string") throw new ClientError("not_authenticated", 401);
  return user.id;
}

function uuid(value: unknown): value is string {
  return typeof value === "string" &&
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
}

function utcDate(): string {
  return new Date().toISOString().slice(0, 10);
}

async function status(userId: string): Promise<Response> {
  const rows = await adminRequest(
    "/rest/v1/prej_user_credentials?select=key_last_four,enabled,verified_at&user_id=eq." + encodeURIComponent(userId),
    "GET",
  ) as Array<{ key_last_four: string; enabled: boolean; verified_at: string | null }>;
  const usage = await adminRequest(
    "/rest/v1/prej_api_usage?select=calls_reserved,calls_completed,last_remaining_reported&user_id=eq." +
      encodeURIComponent(userId) + "&quota_date_utc=eq." + utcDate(),
    "GET",
  ) as Array<{ calls_reserved: number; calls_completed: number; last_remaining_reported: number | null }>;
  const used = usage[0]?.calls_reserved ?? 0;
  return json({
    connected: rows.length > 0 && rows[0].enabled,
    keyLastFour: rows[0]?.key_last_four ?? null,
    verifiedAt: rows[0]?.verified_at ?? null,
    localBudget: DAILY_BUDGET,
    locallyReserved: used,
    locallyAvailable: Math.max(0, DAILY_BUDGET - used),
    providerRemaining: usage[0]?.last_remaining_reported ?? null,
    quotaDateUtc: utcDate(),
  });
}

async function checkProviderKey(userId: string, apiKey: string): Promise<number | null> {
  // Every validation, including a previously stored key, spends one Prej call.
  const reserved = await rpc("prej_reserve_call", {
    p_user_id: userId,
    p_daily_budget: DAILY_BUDGET,
  });
  if (reserved !== true) throw new ClientError("daily_budget_exhausted", 429);

  let check: Response;
  let remaining: number | null = null;
  try {
    check = await fetch(PREJ_BASE + "/v1/stats", {
      headers: { Authorization: "Bearer " + apiKey },
      signal: AbortSignal.timeout(12_000),
    });
    remaining = dailyRemaining(check.headers);
  } catch {
    throw new ClientError("prej_unavailable", 503);
  } finally {
    await rpc("prej_complete_call", { p_user_id: userId, p_remaining: remaining });
  }

  if (check.status === 401) throw new ClientError("invalid_key", 400);
  if (!check.ok) {
    if (check.status === 429) throw new ClientError("prej_rate_limited", 429);
    throw new ClientError("prej_unavailable", 503);
  }
  return remaining;
}

async function storeKey(userId: string, rawKey: unknown): Promise<Response> {
  if (!validPrejKey(rawKey)) throw new ClientError("invalid_key_format", 400);
  const remaining = await checkProviderKey(userId, rawKey);
  await rpc("prej_store_key", { p_user_id: userId, p_api_key: rawKey });
  return json({ connected: true, keyLastFour: rawKey.slice(-4), providerRemaining: remaining });
}

async function verifyStoredKey(userId: string): Promise<Response> {
  const stored = await rpc("prej_read_key", { p_user_id: userId });
  if (typeof stored !== "string" || !stored) throw new ClientError("prej_key_not_configured", 409);
  const remaining = await checkProviderKey(userId, stored);
  // Re-save only after successful provider validation so verified_at remains truthful.
  await rpc("prej_store_key", { p_user_id: userId, p_api_key: stored });
  return json({ connected: true, keyLastFour: stored.slice(-4), providerRemaining: remaining });
}

async function removeKey(userId: string): Promise<Response> {
  // Revoking a key must also revoke all previously granted background consent,
  // so importing another key never silently resumes paid/provider API calls.
  await adminRequest(
    "/rest/v1/prej_nightly_preferences?user_id=eq." + encodeURIComponent(userId),
    "PATCH", { enabled: false, updated_at: new Date().toISOString() },
  );
  await rpc("prej_forget_key", { p_user_id: userId });
  return json({ connected: false });
}

async function verifiedHousehold(userId: string, householdId: unknown): Promise<string> {
  if (!uuid(householdId)) throw new ClientError("invalid_household_id", 400);
  const memberships = await adminRequest(
    "/rest/v1/household_members?select=household_id&user_id=eq." +
      encodeURIComponent(userId) + "&household_id=eq." + encodeURIComponent(householdId),
    "GET",
  ) as unknown[];
  if (!memberships.length) throw new ClientError("not_household_member", 403);
  return householdId;
}

async function discoveryAction(userId: string, householdId: unknown, run: boolean): Promise<Response> {
  const id = await verifiedHousehold(userId, householdId);
  const services = {
    admin: adminRequest,
    rpc,
    fetchPrej: fetch,
    userId,
    householdId: id,
  };
  return json(run ? await discoverProducts(services) : await discoveryStatus(services).then(
    (s) => ({
      total: s.total, chosen: s.chosen, ready: s.ready,
      noMatch: s.noMatch, incomplete: s.incomplete, pending: s.pending, remainingQueries: s.remainingQueries, items: s.items,
    })
  ));
}

async function nightlyStatus(userId: string, householdId: unknown): Promise<Response> {
  const id = await verifiedHousehold(userId, householdId);
  const rows = await adminRequest(
    "/rest/v1/prej_nightly_preferences?select=enabled&user_id=eq." +
      encodeURIComponent(userId) + "&household_id=eq." + encodeURIComponent(id), "GET",
  ) as Array<{ enabled: boolean }>;
  const runs = await adminRequest(
    "/rest/v1/bilka_nightly_runs?select=status,finished_at,products_updated" +
      "&household_id=eq." + encodeURIComponent(id) +
      "&order=local_date.desc&limit=1", "GET",
  ) as Array<{ status: string; finished_at: string | null; products_updated: number }>;
  const schedulerReady = await rpc("bilka_nightly_scheduler_ready", {});
  return json({
    enabled: rows[0]?.enabled === true,
    schedulerReady: schedulerReady === true,
    lastRunStatus: runs[0]?.status ?? null,
    lastUpdatedAt: runs[0]?.finished_at ?? null,
    productsUpdated: runs[0]?.products_updated ?? 0,
  });
}

async function setNightly(userId: string, householdId: unknown, enabled: unknown): Promise<Response> {
  const id = await verifiedHousehold(userId, householdId);
  if (typeof enabled !== "boolean") throw new ClientError("invalid_setting", 400);
  if (enabled) {
    if (await rpc("bilka_nightly_scheduler_ready", {}) !== true) {
      throw new ClientError("nightly_scheduler_not_ready", 409);
    }
    const rows = await adminRequest(
      "/rest/v1/prej_user_credentials?select=verified_at,enabled&user_id=eq." +
        encodeURIComponent(userId) + "&limit=1", "GET",
    ) as Array<{ verified_at: string | null; enabled: boolean }>;
    if (!rows[0]?.enabled || !rows[0]?.verified_at) {
      throw new ClientError("prej_key_not_verified", 409);
    }
  }
  await adminRequest(
    "/rest/v1/prej_nightly_preferences?on_conflict=household_id,user_id",
    "POST",
    [{ household_id: id, user_id: userId, enabled, updated_at: new Date().toISOString() }],
  );
  return json({ enabled });
}

async function refresh(userId: string, householdId: unknown): Promise<Response> {
  if (!uuid(householdId)) throw new ClientError("invalid_household_id", 400);
  const memberships = await adminRequest(
    "/rest/v1/household_members?select=household_id&user_id=eq." +
      encodeURIComponent(userId) + "&household_id=eq." + encodeURIComponent(householdId),
    "GET",
  ) as unknown[];
  if (!memberships.length) throw new ClientError("not_household_member", 403);

  const defaults = await adminRequest(
    "/rest/v1/ingredient_bilka_defaults?select=product_id&household_id=eq." +
      encodeURIComponent(householdId),
    "GET",
  ) as Array<{ product_id: string }>;
  const distinct = [...new Set(defaults.map((d) => d.product_id))];
  if (!distinct.length) return json({ updated: 0, requested: 0, message: "no_selected_products" });

  // Phase 2 refreshes up to one batch. Discovery/queue is phase 3.
  const batches = batchIds(distinct, 500);
  const ids = batches[0] ?? [];
  const existing = await adminRequest(
    "/rest/v1/bilka_products?select=id,price_updated_at&id=in.(" + ids.join(",") + ")",
    "GET",
  ) as Array<{ id: string; price_updated_at: string | null }>;
  const ageCutoff = Date.now() - 20 * 60 * 60 * 1000;
  const recentlyUpdated = new Set(existing.filter((p) =>
    p.price_updated_at && Date.parse(p.price_updated_at) >= ageCutoff
  ).map((p) => p.id));
  const stale = ids.filter((id) => !recentlyUpdated.has(id));
  if (!stale.length) {
    return json({ updated: 0, requested: 0, skippedFresh: ids.length, remainingProducts: distinct.length - ids.length });
  }

  const key = await rpc("prej_read_key", { p_user_id: userId });
  if (typeof key !== "string" || !key) throw new ClientError("prej_key_not_configured", 409);

  const reserved = await rpc("prej_reserve_call", {
    p_user_id: userId,
    p_daily_budget: DAILY_BUDGET,
  });
  if (reserved !== true) throw new ClientError("daily_budget_exhausted", 429);

  let remaining: number | null = null;
  let response: Response;
  try {
    response = await fetch(
      PREJ_BASE + "/v1/products/batch?ids=" + encodeURIComponent(stale.join(",")),
      {
        headers: { Authorization: "Bearer " + key },
        signal: AbortSignal.timeout(20_000),
      },
    );
    remaining = dailyRemaining(response.headers);
  } catch {
    throw new ClientError("prej_unavailable", 503);
  } finally {
    // Reservation counts even when Prej fails, to avoid overspending the free plan.
    await rpc("prej_complete_call", { p_user_id: userId, p_remaining: remaining });
  }

  if (response.status === 429) {
    const error = await response.json().catch(() => ({}));
    throw new ClientError(error?.scope === "burst" ? "prej_burst_limit" : "prej_daily_limit", 429);
  }
  if (response.status === 401) throw new ClientError("prej_key_invalid", 409);
  if (!response.ok) throw new ClientError("prej_unavailable", 503);
  const payload = await response.json();
  if (!Array.isArray(payload?.products)) throw new ClientError("invalid_provider_response", 502);

  const prices = priceOnlyUpdates(payload.products as PrejProduct[], stale);
  // Price-only RPC is atomic and cannot overwrite the chosen product's
  // name, brand, photograph, quantity or household preference.
  const updated = prices.length
    ? await rpc("bilka_update_prices_only", { p_updates: prices })
    : 0;
  return json({
    updated: Number(updated),
    requested: stale.length,
    skippedFresh: ids.length - stale.length,
    remainingProducts: distinct.length - ids.length,
    providerRemaining: remaining,
  });
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return json(null);
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  if (!SUPABASE_URL || !SERVICE_KEY) return json({ error: "server_not_configured" }, 503);
  try {
    const userId = await currentUser(req);
    const input = await req.json().catch(() => ({})) as Payload;
    switch (input.action) {
      case "status": return await status(userId);
      case "set-key": return await storeKey(userId, input.apiKey);
      case "verify-key": return await verifyStoredKey(userId);
      case "remove-key": return await removeKey(userId);
      case "refresh": return await refresh(userId, input.householdId);
      case "nightly-status": return await nightlyStatus(userId, input.householdId);
      case "set-nightly": return await setNightly(userId, input.householdId, input.enabled);
      case "discover": return await discoveryAction(userId, input.householdId, true);
      case "discovery-status": return await discoveryAction(userId, input.householdId, false);
      case "shopping-context": {
        const householdId = await verifiedHousehold(userId, input.householdId);
        return json(await shoppingContext(
          { admin: adminRequest, rpc, householdId },
          typeof input.weekId === "string" ? input.weekId : ""
        ));
      }
      case "record-purchase": {
        const householdId = await verifiedHousehold(userId, input.householdId);
        if (typeof input.weekId !== "string" ||
            typeof input.ingredientId !== "string" ||
            typeof input.amountBase !== "number") {
          return json({ error: "invalid_purchase" }, 400);
        }
        return json(await recordPurchase(
          { admin: adminRequest, rpc, householdId },
          {
            weekId: input.weekId,
            ingredientId: input.ingredientId,
            productId: typeof input.productId === "string" ? input.productId : null,
            manualProductName: typeof input.manualProductName === "string"
              ? input.manualProductName : null,
            packageCount: typeof input.packageCount === "number" ? input.packageCount : null,
            amountBase: input.amountBase,
            paidTotalOre: typeof input.paidTotalOre === "number" ? input.paidTotalOre : null
          }
        ));
      }
      case "catalog": {
        const householdId = await verifiedHousehold(userId, input.householdId);
        if (input.ingredientIds !== undefined &&
            (!Array.isArray(input.ingredientIds) ||
            input.ingredientIds.length > 200 ||
            !input.ingredientIds.every((id: unknown) => uuid(id)))) {
          return json({ error: "invalid_catalog_filter" }, 400);
        }
        return json(await catalog(
          { admin: adminRequest, householdId },
          input.ingredientIds as string[] | undefined,
        ));
      }
      case "manual-search": {
        const householdId = await verifiedHousehold(userId, input.householdId);
        const ingredientId = typeof input.ingredientId === "string" ? input.ingredientId : "";
        const query = typeof input.query === "string" ? input.query : "";
        try {
          return json(await manualProductSearch(
            { admin: adminRequest, rpc, request: fetch, userId, householdId },
            ingredientId, query
          ));
        } catch (error) {
          const code = error instanceof Error ? error.message : "prej_unavailable";
          const known: Record<string, number> = {
            invalid_search: 400, prej_key_not_configured: 409,
            daily_budget_exhausted: 429, prej_key_invalid: 409,
            prej_unavailable: 503
          };
          if (code in known) return json({ error: code }, known[code]);
          throw error;
        }
      }
      case "choose-product": {
        const householdId = await verifiedHousehold(userId, input.householdId);
        const result = await saveHouseholdDefault(
          { admin: adminRequest, householdId },
          typeof input.ingredientId === "string" ? input.ingredientId : "",
          typeof input.productId === "string" ? input.productId : "",
        );
        if (result.error) return json({ error: result.error }, result.status ?? 400);
        return json(result);
      }
      default: return json({ error: "unknown_action" }, 400);
    }
  } catch (err) {
    if (err instanceof ClientError || err instanceof DiscoveryError || err instanceof CheckoutError) return json({ error: err.code }, err.status);
    // No logging: even thrown provider/database errors could contain credentials.
    return json({ error: "internal_error" }, 500);
  }
});
