import {
  dailyRemaining, toBilkaCacheRow, type PrejProduct,
} from "./prej.ts";
import type { CatalogServices } from "./catalog_server.ts";

type Backend = CatalogServices & {
  rpc: (name: string, args: Record<string, unknown>) => Promise<unknown>;
  request: typeof fetch;
  userId: string;
};

const uuid = (value: string) =>
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);

/** GTIN-8, UPC-A (12), EAN-13 or GTIN-14, including valid GS1 check digit. */
export function validGtin(input: string): boolean {
  if (!/^(?:\d{8}|\d{12}|\d{13}|\d{14})$/.test(input)) return false;
  const digits = [...input].map(Number);
  const check = digits.pop()!;
  const sum = digits.reverse().reduce(
    (value, digit, i) => value + digit * (i % 2 === 0 ? 3 : 1), 0,
  );
  return (10 - sum % 10) % 10 === check;
}

function canonicalGtin(value: string): string {
  return value.padStart(14, "0");
}

/** A product may merge multiple barcode variants; use chain-level GTINs. */
export function exactBilkaToGoBarcode(product: PrejProduct, gtin: string): boolean {
  const prices = product.prices ?? [];
  const chain = prices.find((p) => p.chain_slug === "bilkatogo");
  return Array.isArray(chain?.chain_gtins) &&
    chain.chain_gtins.some((known) =>
      typeof known === "string" && /^(?:\d{8}|\d{12}|\d{13}|\d{14})$/.test(known) &&
      canonicalGtin(known) === canonicalGtin(gtin)
    );
}

export function parseScannedProduct(payload: unknown): PrejProduct | null {
  if (typeof payload !== "object" || payload === null) return null;
  const data = payload as Record<string, unknown>;
  const item = data.product ?? data;
  if (typeof item !== "object" || item === null) return null;
  const p = item as PrejProduct;
  return Number.isSafeInteger(p.id) && Number(p.id) > 0 && typeof p.name === "string" &&
    p.name.trim().length > 0 ? p : null;
}

/**
 * Populate exact retailer EAN cache from a normal name-search response.
 * A single name query can thereby unlock multiple future EAN lookups
 * at zero provider cost. Only retailer-supplied chain_gtins are trusted.
 */
export function eanCacheRowsForProducts(
  items: PrejProduct[], now = Date.now(),
): Array<{
  query_key: string; product_ids: string[]; result_total: number;
  page: number; truncated: boolean; expires_at: string;
}> {
  const seen = new Map<string, string>();
  for (const item of items) {
    const priced = toBilkaCacheRow(item, "bilkatogo");
    if (!priced) continue;
    const chain = item.prices?.find((price) => price.chain_slug === "bilkatogo");
    for (const gtin of chain?.chain_gtins ?? []) {
      if (typeof gtin !== "string" || !validGtin(gtin)) continue;
      const cacheKey = "ean:bilkatogo:chain-verified1:" + canonicalGtin(gtin);
      if (!seen.has(cacheKey)) seen.set(cacheKey, priced.id);
      if (seen.size >= 100) break;
    }
    if (seen.size >= 100) break;
  }
  const expiry = new Date(now + 7 * 86400000).toISOString();
  return [...seen.entries()].map(([query_key, productId]) => ({
    query_key, product_ids: [productId], result_total: 1,
    page: 1, truncated: false, expires_at: expiry,
  }));
}

export type BarcodeLookupResult = {
  found: boolean; fromCache: boolean; productId: string | null;
  productName: string | null; exactBilkaToGoMatch: boolean;
};

export async function barcodeProductSearch(
  ctx: Backend, ingredientId: string, rawGtin: string,
): Promise<BarcodeLookupResult> {
  const gtin = rawGtin.replace(/[\s-]/g, "");
  if (!uuid(ingredientId) || !validGtin(gtin)) throw new Error("invalid_gtin");

  const ingredients = await ctx.admin(
    "/rest/v1/ingredients?select=id,never_buy&household_id=eq." +
      encodeURIComponent(ctx.householdId) + "&id=eq." +
      encodeURIComponent(ingredientId) + "&limit=1", "GET",
  ) as Array<{id: string; never_buy: boolean}>;
  if (!ingredients?.length || ingredients[0].never_buy) throw new Error("invalid_search");

  const key = "ean:bilkatogo:chain-verified1:" + canonicalGtin(gtin);
  const cached = await ctx.admin(
    "/rest/v1/bilka_search_cache?select=product_ids,expires_at,result_total" +
      "&query_key=eq." + encodeURIComponent(key) + "&limit=1", "GET",
  ) as Array<{product_ids: string[]; expires_at: string; result_total: number}>;

  let productId: string | null = null;
  let productName: string | null = null;
  let fromCache = false;
  if (cached?.length && Date.parse(cached[0].expires_at) > Date.now()) {
    fromCache = true;
    productId = (cached[0].product_ids ?? [])
      .find((id) => /^bilkatogo:\d+$/.test(id)) ?? null;
    if (productId) {
      const products = await ctx.admin(
        "/rest/v1/bilka_products?select=id,name,price_ore,chain_slug&id=eq." +
          encodeURIComponent(productId) + "&chain_slug=eq.bilkatogo&limit=1", "GET",
      ) as Array<{ id: string; name: string; price_ore: number | null; chain_slug: string }>;
      if (products?.length && products[0].price_ore !== null) {
        productName = products[0].name;
      } else {
        // A removed/unpriced cache hit is not a valid shopping product.
        productId = null;
      }
    }
  } else {
    const apiKey = await ctx.rpc("prej_read_key", { p_user_id: ctx.userId });
    if (typeof apiKey !== "string" || !apiKey) throw new Error("prej_key_not_configured");
    const reserved = await ctx.rpc("prej_reserve_call", {
      p_user_id: ctx.userId, p_daily_budget: 20,
    });
    if (reserved !== true) throw new Error("daily_budget_exhausted");

    let response: Response;
    let remaining: number | null = null;
    try {
      response = await ctx.request(
        "https://api.prej.app/v1/scan/" + gtin, {
          headers: { Authorization: "Bearer " + apiKey },
          signal: AbortSignal.timeout(12_000),
        },
      );
      remaining = dailyRemaining(response.headers);
    } catch {
      throw new Error("prej_unavailable");
    } finally {
      await ctx.rpc("prej_complete_call", {
        p_user_id: ctx.userId, p_remaining: remaining,
      });
    }

    if (response.status === 429) throw new Error("daily_budget_exhausted");
    if (response.status === 401) throw new Error("prej_key_invalid");
    if (!response.ok && response.status !== 404) throw new Error("prej_unavailable");

    const payload: unknown = response.status === 404
      ? null : await response.json().catch(() => null);
    if (response.status !== 404 && payload === null) throw new Error("prej_unavailable");
    const product = parseScannedProduct(payload);
    if (product && exactBilkaToGoBarcode(product, gtin)) {
      const row = toBilkaCacheRow(product, "bilkatogo");
      if (row) {
        await ctx.admin("/rest/v1/bilka_products?on_conflict=id", "POST", [row]);
        productId = row.id;
        productName = row.name;
      }
    }
    // Cache both positive and negative lookup; never claim a different
    // chain's price or an unverified variant matches this EAN.
    await ctx.admin("/rest/v1/bilka_search_cache?on_conflict=query_key",
      "POST", [{
        query_key: key, product_ids: productId ? [productId] : [],
        result_total: productId ? 1 : 0, page: 1, truncated: false,
        expires_at: new Date(Date.now() + (productId ? 7 : 1) * 86400000).toISOString(),
      }]);
  }

  if (productId && productName) {
    await ctx.admin(
      "/rest/v1/ingredient_bilka_candidates?on_conflict=household_id,ingredient_id,product_id",
      "POST", [{
        household_id: ctx.householdId, ingredient_id: ingredientId,
        product_id: productId, match_status: "SUGGESTED",
        match_score: null, display_rank: 0,
      }], "resolution=ignore-duplicates,return=minimal",
    );
  }
  return {
    found: Boolean(productId && productName), fromCache, productId,
    productName, exactBilkaToGoMatch: Boolean(productId && productName),
  };
}
