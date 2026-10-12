import { batchIds, dailyRemaining, toBilkaCacheRow, providerProductId, isStoredProductId, type GroceryChain, type PrejProduct, type BilkaCacheRow } from "./prej.ts";

export type Admin = (path: string, method: "GET" | "POST" | "PATCH", body?: unknown, prefer?: string) => Promise<unknown>;
export type Rpc = (name: string, args: Record<string, unknown>) => Promise<unknown>;
export type NightlyDeps = { admin: Admin; rpc: Rpc; providerFetch: typeof fetch; now: Date };
type Preference = { household_id: string; user_id: string; created_at: string; enabled: boolean };
type Credential = { user_id: string; enabled: boolean; verified_at: string | null };
type Membership = { household_id: string; user_id: string };
type Default = { household_id: string; product_id: string };
type Run = { household_id: string; local_date: string };
export type NightlyOwner = { householdId: string; userId: string; productIds: string[] };
const list = <T>(value: unknown): T[] => Array.isArray(value) ? value as T[] : [];

export function copenhagenLocal(now: Date): { localDate: string; hour: number } {
  const parts = new Intl.DateTimeFormat("en-GB", {
    timeZone: "Europe/Copenhagen", year: "numeric", month: "2-digit", day: "2-digit",
    hour: "2-digit", hourCycle: "h23",
  }).formatToParts(now);
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? "";
  return { localDate: get("year") + "-" + get("month") + "-" + get("day"), hour: Number(get("hour")) };
}

export function pickNightlyOwners(
  prefs: Preference[], credentials: Credential[], memberships: Membership[],
  defaults: Default[], pastRuns: Run[], localDate: string,
): NightlyOwner[] {
  const verified = new Set(credentials.filter((c) => c.enabled && !!c.verified_at).map((c) => c.user_id));
  const membership = new Set(memberships.map((m) => m.household_id + "/" + m.user_id));
  const already = new Set(pastRuns.filter((r) => r.local_date === localDate).map((r) => r.household_id));
  const products = new Map<string, Set<string>>();
  for (const d of defaults) {
    if (!isStoredProductId(d.product_id)) continue;
    if (!products.has(d.household_id)) products.set(d.household_id, new Set());
    products.get(d.household_id)!.add(d.product_id);
  }
  const owners = new Map<string, NightlyOwner>();
  // Choose one fixed first-consenting account per household. Never rotate keys.
  for (const p of [...prefs].sort((a, b) =>
    a.created_at.localeCompare(b.created_at) || a.user_id.localeCompare(b.user_id)
  )) {
    if (!p.enabled || !verified.has(p.user_id) ||
      !membership.has(p.household_id + "/" + p.user_id) ||
      already.has(p.household_id) || owners.has(p.household_id)) continue;
    const ids = [...(products.get(p.household_id) ?? new Set<string>())];
    if (ids.length) owners.set(p.household_id, { householdId: p.household_id, userId: p.user_id, productIds: ids });
  }
  return [...owners.values()];
}

export async function performNightly(deps: NightlyDeps) {
  const clock = copenhagenLocal(deps.now);
  if (clock.hour < 5 || clock.hour > 7)
    return { processed: 0, updated: 0, skipped: "outside_local_window" };
  const [prefs, creds, members, defaults, history] = await Promise.all([
    deps.admin("/rest/v1/prej_nightly_preferences?select=household_id,user_id,created_at,enabled&enabled=eq.true&limit=1000", "GET"),
    deps.admin("/rest/v1/prej_user_credentials?select=user_id,enabled,verified_at&limit=1000", "GET"),
    deps.admin("/rest/v1/household_members?select=household_id,user_id&limit=1000", "GET"),
    deps.admin("/rest/v1/ingredient_bilka_defaults?select=household_id,product_id&limit=20000", "GET"),
    deps.admin("/rest/v1/bilka_nightly_runs?select=household_id,local_date&local_date=eq." + clock.localDate + "&limit=1000", "GET"),
  ]);
  const eligible = pickNightlyOwners(
    list<Preference>(prefs), list<Credential>(creds), list<Membership>(members),
    list<Default>(defaults), list<Run>(history), clock.localDate,
  );
  let processed = 0, updated = 0, failures = 0;
  for (const owner of eligible.slice(0, 3)) {
    // PostgREST ignore-duplicates returns [] if another worker already claimed today.
    const claim = await deps.admin(
      "/rest/v1/bilka_nightly_runs?on_conflict=household_id,local_date",
      "POST", [{
        household_id: owner.householdId, local_date: clock.localDate,
        attempted_by: owner.userId, status: "RUNNING",
      }], "resolution=ignore-duplicates,return=representation"
    );
    if (!list(claim).length) continue;
    let count = 0, requests = 0, error: string | null = null;
    const chunks = batchIds(owner.productIds.map(providerProductId), 500);
    try {
      const key = await deps.rpc("prej_read_key", { p_user_id: owner.userId });
      if (typeof key !== "string" || !key) throw new Error("key_not_available");
      for (const ids of chunks.slice(0, 2)) {
        const reserved = await deps.rpc("prej_reserve_call", {
          p_user_id: owner.userId, p_daily_budget: 20,
        });
        if (reserved !== true) throw new Error("quota_exhausted");
        requests++;
        let response: Response;
        let remaining: number | null = null;
        try {
          response = await deps.providerFetch(
            "https://api.prej.app/v1/products/batch?ids=" + encodeURIComponent(ids.join(",")), {
              headers: { Authorization: "Bearer " + key },
              signal: AbortSignal.timeout(15_000),
            }
          );
          remaining = dailyRemaining(response.headers);
        } finally {
          await deps.rpc("prej_complete_call", { p_user_id: owner.userId, p_remaining: remaining });
        }
        if (response.status === 429) throw new Error("provider_limit");
        if (response.status === 401) throw new Error("invalid_key");
        if (!response.ok) throw new Error("provider_unavailable");
        const body = await response.json().catch(() => null);
        if (!body || !Array.isArray(body.products)) throw new Error("invalid_provider_response");
        const requested = new Set(
          owner.productIds.filter((id) => ids.includes(providerProductId(id))),
        );
        const prices = (body.products as PrejProduct[])
          .flatMap((product) =>
            (["bilka", "bilkatogo"] as GroceryChain[])
              .map((chain) => toBilkaCacheRow(product, chain))
          )
          .filter((r): r is BilkaCacheRow => r !== null && requested.has(r.id))
          .map((r) => ({
            id: r.id, price_ore: r.price_ore, unit_price_ore: r.unit_price_ore,
            unit_price_basis: r.unit_price_basis, is_offer: r.is_offer,
            price_updated_at: r.price_updated_at,
          }));
        if (prices.length) count += Number(await deps.rpc("bilka_update_prices_only", { p_updates: prices })) || 0;
      }
      if (chunks.length > 2) error = "batch_limit";
    } catch (e) {
      // Do not return or log provider/database errors (may contain credentials).
      error = e instanceof Error && [
        "quota_exhausted", "provider_limit", "invalid_key",
        "invalid_provider_response", "key_not_available", "batch_limit",
      ].includes(e.message) ? e.message : "refresh_failed";
    }
    await deps.admin(
      "/rest/v1/bilka_nightly_runs?household_id=eq." + owner.householdId +
      "&local_date=eq." + clock.localDate,
      "PATCH", {
        status: error ? (count > 0 ? "PARTIAL" : "FAILED") : "COMPLETE",
        products_updated: count, request_count: requests,
        error_code: error, finished_at: new Date().toISOString(),
      }
    );
    processed++; updated += count;
    if (error) failures++;
  }
  return { processed, updated, failures };
}
