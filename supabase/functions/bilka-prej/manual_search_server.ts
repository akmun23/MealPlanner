import { dailyRemaining, toBilkaCacheRow, type PrejProduct } from "./prej.ts";
import { type CatalogServices } from "./catalog_server.ts";


type Backend = CatalogServices & {
  rpc: (name: string, args: Record<string, unknown>) => Promise<unknown>;
  request: typeof fetch;
  userId: string;
};
const isUuid = (value: string) =>
  /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(value);

export async function manualProductSearch(
  ctx: Backend, ingredientId: string, text: string, page = 1
): Promise<{ count: number; page: number; fromCache: boolean; providerTotal: number | null; receivedCount: number | null; afterCategory: number | null; withToGoPrice: number | null; hasMore: boolean }> {
  const query = text.trim().replace(/\s+/g, " ").slice(0, 80);
  if (!isUuid(ingredientId) || query.length < 2 || !Number.isInteger(page) || page < 1 || page > 1000) throw new Error("invalid_search");
  const household = encodeURIComponent(ctx.householdId);
  const ingredients = await ctx.admin(
    "/rest/v1/ingredients?select=id,name,never_buy&household_id=eq." + household +
    "&id=eq." + encodeURIComponent(ingredientId) + "&limit=1", "GET"
  ) as Array<{id: string; name: string; never_buy: boolean}>;
  if (!ingredients?.length || ingredients[0].never_buy) throw new Error("invalid_search");

  const key = "q:bilkatogo:raw4:" + query.toLowerCase() + ":p" + page;
  const cached = await ctx.admin(
    "/rest/v1/bilka_search_cache?select=query_key,product_ids,expires_at,result_total" +
    "&query_key=eq." + encodeURIComponent(key) + "&limit=1",
    "GET"
  ) as Array<{product_ids: string[]; expires_at: string; result_total: number}>;

  let products: Array<{ id: string; name: string; unit_price_ore?: number | null }> = [];
  let totalFound: number | null = null;
  let fromCache = false;
  let providerTotal: number | null = null;
  let receivedCount: number | null = null;
  let afterCategory: number | null = null;
  let withToGoPrice: number | null = null;
  if (cached?.length && Date.parse(cached[0].expires_at) > Date.now()) {
    fromCache = true;
    totalFound = cached[0].result_total;
    const ids = cached[0].product_ids.filter((id) => /^bilkatogo:\d+$/.test(id)).slice(0, 100);
    if (ids.length) {
      // Cached IDs alone cannot be checked for ingredient relevance.
      products = await ctx.admin(
        "/rest/v1/bilka_products?select=id,name,unit_price_ore&chain_slug=eq.bilkatogo" +
          "&id=in.(" + ids.map(encodeURIComponent).join(",") + ")&limit=100",
        "GET",
      ) as Array<{ id: string; name: string; unit_price_ore?: number | null }>;
      const byId = new Map(products.map((p) => [p.id,p]));
      products = ids.map((id) => byId.get(id)).filter((p): p is NonNullable<typeof p> => !!p);
    }
  } else {
    const apiKey = await ctx.rpc("prej_read_key", { p_user_id: ctx.userId });
    if (typeof apiKey !== "string" || !apiKey) throw new Error("prej_key_not_configured");
    const reserved = await ctx.rpc("prej_reserve_call", {
      p_user_id: ctx.userId,
      p_daily_budget: 20
    });
    if (reserved !== true) throw new Error("daily_budget_exhausted");
    let response: Response;
    let remaining: number | null = null;
    try {
      const args = new URLSearchParams({
        chain: "bilkatogo", q: query, page: String(page), limit: "100"
      });
      response = await ctx.request(
        "https://api.prej.app/v1/products?" + args.toString(),
        {
          headers: { Authorization: "Bearer " + apiKey },
          signal: AbortSignal.timeout(12_000)
        }
      );
      remaining = dailyRemaining(response.headers);
    } catch {
      throw new Error("prej_unavailable");
    } finally {
      await ctx.rpc("prej_complete_call", {
        p_user_id: ctx.userId, p_remaining: remaining
      });
    }
    if (response.status === 429) throw new Error("daily_budget_exhausted");
    if (response.status === 401) throw new Error("prej_key_invalid");
    if (!response.ok) throw new Error("prej_unavailable");

    const payload = await response.json().catch(() => null);
    if (!payload || !Array.isArray(payload.products)) throw new Error("prej_unavailable");
    // Diagnostics explain missing suggestions without logging the user's
    // Prej key or paying for an extra API request.
    const providerProducts = payload.products as PrejProduct[];
    providerTotal = Number.isSafeInteger(payload.total) ? payload.total : providerProducts.length;
    receivedCount = providerProducts.length;
    // Never silently discard products due to name/category relevance. Display
    // every SKU in the requested chain with an actual ToGo price, in provider
    // order. Other-chain prices must NOT be passed off as BilkaToGo.
    afterCategory = providerProducts.length;
    const valid = providerProducts
      .map((p) => toBilkaCacheRow(p, "bilkatogo"))
      .filter((p): p is NonNullable<typeof p> => p !== null);
    withToGoPrice = valid.length;
    totalFound = providerTotal;
    const unique = [...new Map(valid.map((p) => [p.id, p])).values()];
    if (unique.length) {
      await ctx.admin("/rest/v1/bilka_products?on_conflict=id", "POST", unique);
    }
    products = unique;
    await ctx.admin("/rest/v1/bilka_search_cache?on_conflict=query_key", "POST", [{
      query_key: key,
      product_ids: unique.map((p) => p.id),
      result_total: Number.isSafeInteger(payload.total) ? payload.total : unique.length,
      page,
      truncated: payload.total > page * 100,
      expires_at: new Date(Date.now() + 7 * 86400000).toISOString()
    }]);
  }

  // All page results are user-visible candidates. No product relevance
  // filtering or automatic selection. The raw search intentionally allows
  // unrelated items; users decide what to pick. Ranking is provider order.
  const visible = products.slice(0, 100);
  if (visible.length) {
    await ctx.admin(
      "/rest/v1/ingredient_bilka_candidates?on_conflict=household_id,ingredient_id,product_id",
      "POST",
      visible.map((p, i) => ({
        household_id: ctx.householdId,
        ingredient_id: ingredientId,
        product_id: p.id,
        match_status: "SUGGESTED",
        match_score: null,
        display_rank: (page - 1) * 100 + i,
      })),
      "resolution=ignore-duplicates,return=minimal",
    );
  }
  await ctx.admin(
    "/rest/v1/bilka_discovery_jobs?on_conflict=household_id,ingredient_id",
    "POST",
    [{
      household_id: ctx.householdId,
      ingredient_id: ingredientId,
      search_query: query,
      status: visible.length ? "COMPLETE" : "NO_MATCH"
    }]
  );
  return {
    count: visible.length, page, fromCache,
    providerTotal: totalFound, receivedCount, afterCategory, withToGoPrice,
    hasMore: totalFound !== null && page * 100 < totalFound,
  };
}
