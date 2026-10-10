import { groupSearches, rankCandidates, cleanedExactQuery, type IngredientNeed } from "./discovery.ts";
import { dailyRemaining, toBilkaCacheRow, type BilkaCacheRow, type PrejProduct } from "./prej.ts";

type QueryCache = {
  query_key: string;
  product_ids: string[];
  result_total: number;
  truncated: boolean;
  expires_at: string;
};
type Job = { id?: string; ingredient_id: string; status: string; attempts: number };
type Candidate = { ingredient_id: string; product_id: string; match_status: string };
type IngredientRow = IngredientNeed & { never_buy: boolean };

export class DiscoveryError extends Error {
  constructor(public code: string, public status: number) {
    super(code);
  }
}

export type DiscoveryServices = {
  admin: (path: string, method: "GET" | "POST", body?: unknown, prefer?: string) => Promise<unknown>;
  rpc: (name: string, args: Record<string, unknown>) => Promise<unknown>;
  fetchPrej: typeof fetch;
  userId: string;
  householdId: string;
};

/** One import attempt is bounded by calls AND elapsed time; never spend 25 calls at once. */
const REQUESTS_PER_RUN = 8;
const DISCOVERY_DAILY_LIMIT = 18; // Keep >= 2 app-budget slots for price refresh.
const CACHE_DAYS = 7;

const qs = (value: string) => encodeURIComponent(value);
const arrayOf = <T>(value: unknown): T[] => Array.isArray(value) ? value as T[] : [];
const idsFilter = (ids: string[]) => "id=in.(" + ids.join(",") + ")";

function cacheProducts(rows: BilkaCacheRow[]): PrejProduct[] {
  return rows.map((row) => ({
    id: Number(row.id),
    name: row.name,
    brand: row.brand,
    image_url: row.image_url,
    quantity: row.package_quantity,
    unit: row.package_unit,
    prices: [{
      chain_slug: "bilka",
      price: row.price_ore,
      unit_price: row.unit_price_ore,
      quantity: row.package_quantity,
      unit: row.package_unit,
    }],
  }));
}

export async function discoveryStatus(ctx: DiscoveryServices) {
  const { admin, householdId } = ctx;
  const ingredients = arrayOf<IngredientRow>(await admin(
    "/rest/v1/ingredients?select=id,name,never_buy&household_id=eq." + qs(householdId) +
      "&never_buy=eq.false&limit=1000", "GET",
  ));
  const defaults = arrayOf<{ ingredient_id: string }>(await admin(
    "/rest/v1/ingredient_bilka_defaults?select=ingredient_id&household_id=eq." +
      qs(householdId) + "&limit=1000", "GET",
  ));
  const candidates = arrayOf<Candidate>(await admin(
    "/rest/v1/ingredient_bilka_candidates?select=ingredient_id,product_id,match_status&household_id=eq." +
      qs(householdId) + "&limit=5000", "GET",
  ));
  const jobs = arrayOf<Job>(await admin(
    "/rest/v1/bilka_discovery_jobs?select=id,ingredient_id,status,attempts&household_id=eq." +
      qs(householdId) + "&limit=1000", "GET",
  ));
  const chosen = new Set(defaults.map((d) => d.ingredient_id));
  const ready = new Set(candidates.filter((c) => c.match_status === "SUGGESTED")
    .map((c) => c.ingredient_id));
  const noMatch = new Set(jobs.filter((j) => j.status === "NO_MATCH")
    .map((j) => j.ingredient_id));
  const incomplete = new Set(jobs.filter((j) => j.status === "INCOMPLETE")
    .map((j) => j.ingredient_id));
  const pending = ingredients.filter((i) =>
    !chosen.has(i.id) && !ready.has(i.id) && !noMatch.has(i.id) && !incomplete.has(i.id));
  const jobsById = new Map(jobs.map((j) => [j.ingredient_id, j]));
  const candidateCounts = new Map<string, number>();
  for (const candidate of candidates) {
    if (candidate.match_status === "SUGGESTED") {
      candidateCounts.set(candidate.ingredient_id, (candidateCounts.get(candidate.ingredient_id) ?? 0) + 1);
    }
  }
  const items = ingredients.map((ingredient) => ({
    ingredientId: ingredient.id,
    name: ingredient.name,
    state: chosen.has(ingredient.id) ? "CHOSEN" :
      ready.has(ingredient.id) ? "READY" :
      incomplete.has(ingredient.id) ? "REFINE" :
      noMatch.has(ingredient.id) ? "NO_MATCH" : "PENDING",
    candidateCount: candidateCounts.get(ingredient.id) ?? 0,
  })).sort((a, b) => a.name.localeCompare(b.name, "da"));
  return {
    total: ingredients.length,
    chosen: ingredients.filter((i) => chosen.has(i.id)).length,
    ready: ingredients.filter((i) => !chosen.has(i.id) && ready.has(i.id)).length,
    noMatch: ingredients.filter((i) => !chosen.has(i.id) && noMatch.has(i.id)).length,
    incomplete: ingredients.filter((i) => !chosen.has(i.id) && incomplete.has(i.id)).length,
    pending: pending.length,
    remainingQueries: groupSearches(pending).length,
    items,
    pendingIngredients: pending,
    jobsById,
  };
}

/** Only suggests SKUs. Never writes ingredient_bilka_defaults. */
export async function discoverProducts(ctx: DiscoveryServices) {
  const { admin, rpc, householdId, userId } = ctx;
  const initial = await discoveryStatus(ctx);
  if (!initial.pending) {
    return { ...stripInternal(initial), requestsUsed: 0, fromCache: 0, addedCandidates: 0 };
  }

  const caches = arrayOf<QueryCache>(await admin(
    "/rest/v1/bilka_search_cache?select=query_key,product_ids,result_total,truncated,expires_at&limit=1000",
    "GET",
  ));
  const queryCache = new Map(caches.map((c) => [c.query_key, c]));
  const known = new Set<string>();
  const jobs = initial.jobsById;
  const jobRows = initial.pendingIngredients.filter((i) => !jobs.has(i.id)).map((i) => ({
    household_id: householdId,
    ingredient_id: i.id,
    search_query: cleanedExactQuery(i.name),
    status: "PENDING",
    attempts: 0,
  }));
  if (jobRows.length) {
    await admin("/rest/v1/bilka_discovery_jobs?on_conflict=household_id,ingredient_id",
      "POST", jobRows);
  }

  const deadline = Date.now() + 25_000; // Keep one button press responsive.
  let calls = 0;
  let fromCache = 0;
  let addedCandidates = 0;
  let stopped = false;
  let stopReason: string | null = null;
  const done = new Set<string>();
  const remaining = new Map(initial.pendingIngredients.map((i) => [i.id, i]));
  const jobUpdates = new Map<string, { status: string; attempts: number }>();

  async function upsertSuggestions(members: IngredientNeed[], products: PrejProduct[]) {
    const out: Array<Record<string, unknown>> = [];
    for (const ingredient of members) {
      if (!remaining.has(ingredient.id)) continue;
      const ranks = rankCandidates(ingredient.name, products, 10);
      if (!ranks.length) continue;
      ranks.forEach((candidate, rank) => {
        if (!candidate.product.id || !known.has(String(candidate.product.id))) return;
        out.push({
          household_id: householdId,
          ingredient_id: ingredient.id,
          product_id: String(candidate.product.id),
          match_status: "SUGGESTED",
          match_score: candidate.score,
          display_rank: rank,
        });
      });
      if (out.some((r) => r.ingredient_id === ingredient.id)) {
        done.add(ingredient.id);
        remaining.delete(ingredient.id);
        jobUpdates.set(ingredient.id, { status: "COMPLETE", attempts: jobs.get(ingredient.id)?.attempts ?? 0 });
      }
    }
    if (out.length) {
      // Ignore an existing rejected choice; phase 4 must never silently resurrect it.
      await admin(
        "/rest/v1/ingredient_bilka_candidates?on_conflict=household_id,ingredient_id,product_id",
        "POST", out, "resolution=ignore-duplicates,return=minimal",
      );
      addedCandidates += out.length;
    }
  }

  async function lookup(query: string): Promise<PrejProduct[] | null> {
    const key = "q:bilka:grocery1:" + query.toLowerCase().trim();
    const existing = queryCache.get(key);
    if (existing && Date.parse(existing.expires_at) > Date.now()) {
      fromCache++;
      const ids = (existing.product_ids ?? []).filter((id) => /^[0-9]+$/.test(id));
      if (!ids.length) return [];
      const cached = arrayOf<BilkaCacheRow>(await admin(
        "/rest/v1/bilka_products?select=*&" + idsFilter(ids) + "&limit=100", "GET",
      ));
      cached.forEach((c) => known.add(c.id));
      return cacheProducts(cached);
    }
    if (calls >= REQUESTS_PER_RUN || Date.now() >= deadline) {
      stopped = true;
      stopReason = "run_call_limit";
      return null;
    }

    const prejKey = await rpc("prej_read_key", { p_user_id: userId });
    if (typeof prejKey !== "string" || !prejKey) {
      stopped = true;
      stopReason = "no_api_key";
      return null;
    }

    const reserved = await rpc("prej_reserve_call", {
      p_user_id: userId,
      p_daily_budget: DISCOVERY_DAILY_LIMIT,
    });
    if (reserved !== true) {
      stopped = true;
      stopReason = "daily_budget_exhausted";
      return null;
    }

    calls++;
    let response: Response;
    let providerRemaining: number | null = null;
    try {
      const params = new URLSearchParams({
        chain: "bilka",
        grocery: "1",
        q: query,
        page: "1",
        limit: "100",
      });
      response = await ctx.fetchPrej(
        "https://api.prej.app/v1/products?" + params.toString(),
        {
          headers: { Authorization: "Bearer " + prejKey },
          signal: AbortSignal.timeout(12_000),
        },
      );
      providerRemaining = dailyRemaining(response.headers);
    } catch {
      stopped = true;
      stopReason = "prej_unavailable";
      return null;
    } finally {
      await rpc("prej_complete_call", { p_user_id: userId, p_remaining: providerRemaining });
    }

    if (response.status === 429) {
      stopped = true;
      const error = await response.json().catch(() => ({}));
      stopReason = error?.scope === "burst" ? "prej_burst_limit" : "prej_daily_limit";
      return null;
    }
    if (response.status === 401) {
      stopped = true;
      stopReason = "prej_key_invalid";
      return null;
    }
    if (!response.ok) {
      stopped = true;
      stopReason = "prej_unavailable";
      return null;
    }

    const payload = await response.json().catch(() => null);
    if (!payload || !Array.isArray(payload.products)) {
      stopped = true;
      stopReason = "invalid_provider_response";
      return null;
    }
    const products = payload.products as PrejProduct[];
    const rows = products.map(toBilkaCacheRow)
      .filter((row): row is BilkaCacheRow => row !== null);
    const uniqueRows = [...new Map(rows.map((row) => [row.id, row])).values()];
    if (uniqueRows.length) {
      await admin("/rest/v1/bilka_products?on_conflict=id", "POST", uniqueRows);
      uniqueRows.forEach((row) => known.add(row.id));
    }
    const total = Number.isSafeInteger(payload.total) ? payload.total : uniqueRows.length;
    const cache: QueryCache = {
      query_key: key,
      product_ids: uniqueRows.map((row) => row.id),
      result_total: total,
      truncated: total > 100,
      expires_at: new Date(Date.now() + CACHE_DAYS * 86400_000).toISOString(),
    };
    await admin("/rest/v1/bilka_search_cache?on_conflict=query_key", "POST", [cache]);
    queryCache.set(key, cache);
    return products.filter((product) => known.has(String(product.id)));
  }

  // Stage 1: shared/broad terms. One response may suggest products for many ingredients.
  for (const group of groupSearches([...remaining.values()])) {
    if (stopped) break;
    const query = group.query;
    const products = await lookup(query);
    if (products === null) break;
    await upsertSuggestions(group.ingredients, products);
  }

  // Stage 2: targeted fallback only for ingredients still without candidates.
  // A broad 100-result page is never assumed to cover every possible product.
  for (const ingredient of [...remaining.values()]) {
    if (stopped) break;
    const query = cleanedExactQuery(ingredient.name);
    const groupQuery = groupSearches([ingredient])[0]?.query;
    if (!query || !groupQuery) continue;
    const sameAsGroup = query.toLowerCase() === groupQuery.toLowerCase();
    const products = await lookup(query);
    if (products === null) break;
    await upsertSuggestions([ingredient], products);
    if (remaining.has(ingredient.id)) {
      const info = queryCache.get("q:" + query.toLowerCase().trim());
      // With 100 results/page, a truncated list is NOT evidence of no product.
      // Phase 4 will let the user narrow the search explicitly.
      jobUpdates.set(ingredient.id, {
        status: info?.truncated ? "INCOMPLETE" : "NO_MATCH",
        attempts: (jobs.get(ingredient.id)?.attempts ?? 0) + (sameAsGroup ? 1 : 2),
      });
    }
  }

  const updates = [...jobUpdates.entries()].map(([ingredientId, update]) => ({
    household_id: householdId,
    ingredient_id: ingredientId,
    search_query: cleanedExactQuery(initial.pendingIngredients.find((i) => i.id === ingredientId)?.name ?? ""),
    status: update.status,
    attempts: update.attempts,
    last_error: null,
    updated_at: new Date().toISOString(),
  }));
  if (updates.length) {
    await admin("/rest/v1/bilka_discovery_jobs?on_conflict=household_id,ingredient_id",
      "POST", updates);
  }
  const status = await discoveryStatus(ctx);
  return {
    ...stripInternal(status),
    requestsUsed: calls,
    fromCache,
    addedCandidates,
    stopReason,
    runLimit: REQUESTS_PER_RUN,
    canContinue: status.pending > 0,
  };
}

function stripInternal(value: Awaited<ReturnType<typeof discoveryStatus>>) {
  const { total, chosen, ready, noMatch, incomplete, pending, remainingQueries, items } = value;
  return { total, chosen, ready, noMatch, incomplete, pending, remainingQueries, items };
}
