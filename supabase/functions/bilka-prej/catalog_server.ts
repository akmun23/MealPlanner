/**
 * Household-scoped Bilka product catalogue. Read-only suggestions and explicit
 * user confirmation are deliberately separate from Prej product discovery.
 */
export interface ProductChoice {
  ingredient_id: string;
  product_id: string;
}
export type CatalogServices = {
  admin: (path: string, method: "GET" | "POST", body?: unknown, prefer?: string) => Promise<unknown>;
  householdId: string;
};
const encoded = (value: string) => encodeURIComponent(value);
const idRe = /^[0-9]+$/;
const uuidRe = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const list = <T>(data: unknown): T[] => Array.isArray(data) ? data as T[] : [];

export function eligibleChoice(
  ingredientId: string,
  productId: string,
  options: ProductChoice[],
): boolean {
  return uuidRe.test(ingredientId) && idRe.test(productId) &&
    options.some((candidate) =>
      candidate.ingredient_id === ingredientId &&
      candidate.product_id === productId
    );
}

export async function catalog(ctx: CatalogServices) {
  const household = encoded(ctx.householdId);
  const [candidatesRaw, defaultsRaw] = await Promise.all([
    ctx.admin("/rest/v1/ingredient_bilka_candidates?select=ingredient_id,product_id,display_rank" +
      "&household_id=eq." + household + "&match_status=eq.SUGGESTED&limit=5000", "GET"),
    ctx.admin("/rest/v1/ingredient_bilka_defaults?select=ingredient_id,product_id" +
      "&household_id=eq." + household + "&limit=1000", "GET"),
  ]);
  const candidates = list<{ ingredient_id: string; product_id: string; display_rank: number }>(candidatesRaw);
  const defaults = list<ProductChoice>(defaultsRaw);
  const ids = [...new Set([...candidates, ...defaults].map((c) => c.product_id))]
    .filter((id) => idRe.test(id));
  const chunks = Array.from({ length: Math.ceil(ids.length / 100) }, (_, i) =>
    ids.slice(i * 100, (i + 1) * 100));

  // Products are public cache data, but never return unrelated households'
  // selected defaults, candidates or chosen ingredient IDs.
  const products = (await Promise.all(chunks.map((batch) =>
    ctx.admin("/rest/v1/bilka_products?select=id,name,brand,chain_slug," +
      "package_quantity,package_unit,package_base_amount,quantity_kind,image_url," +
      "price_ore,unit_price_ore,unit_price_basis,is_offer,is_available,price_updated_at" +
      "&chain_slug=eq.bilka&id=in.(" + batch.join(",") + ")&limit=100", "GET")
  ))).flatMap((x) => list<Record<string, unknown>>(x));

  return { candidates, defaults, products };
}

export async function saveHouseholdDefault(
  ctx: CatalogServices,
  ingredientId: string,
  productId: string,
) {
  const house = encoded(ctx.householdId);
  if (!uuidRe.test(ingredientId) || !idRe.test(productId)) {
    return { error: "invalid_product_choice", status: 400 };
  }
  const candidateRows = list<ProductChoice>(await ctx.admin(
    "/rest/v1/ingredient_bilka_candidates?select=ingredient_id,product_id" +
      "&household_id=eq." + house +
      "&ingredient_id=eq." + encoded(ingredientId) +
      "&product_id=eq." + encoded(productId) +
      "&match_status=eq.SUGGESTED&limit=1", "GET",
  ));
  if (!eligibleChoice(ingredientId, productId, candidateRows)) {
    return { error: "not_a_suggested_product", status: 400 };
  }
  await ctx.admin(
    "/rest/v1/ingredient_bilka_defaults?on_conflict=household_id,ingredient_id",
    "POST",
    [{
      household_id: ctx.householdId,
      ingredient_id: ingredientId,
      product_id: productId,
    }],
  );
  return { saved: true, ingredientId, productId };
}
