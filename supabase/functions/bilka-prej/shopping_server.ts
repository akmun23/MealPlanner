/** Checkout is separate from permanent household product preferences. */
type Admin = (path: string, method: "GET" | "POST", body?: unknown, prefer?: string) => Promise<unknown>;
type Rpc = (name: string, args: Record<string, unknown>) => Promise<unknown>;
export type ShoppingService = {
  admin: Admin;
  rpc: Rpc;
  householdId: string;
};
const uuid = (s: string) => /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(s);
const onlyNumber = (s: string) => /^\d+$/.test(s);
const list = <T>(x: unknown): T[] => Array.isArray(x) ? x as T[] : [];
const encode = (v: string) => encodeURIComponent(v);

export class CheckoutError extends Error {
  constructor(public code: string, public status: number) { super(code); }
}
async function checkedWeek(ctx: ShoppingService, weekId: string) {
  if (!uuid(weekId)) throw new CheckoutError("invalid_week", 400);
  const rows = list(await ctx.admin(
    "/rest/v1/weekly_plans?select=id&household_id=eq." + encode(ctx.householdId) +
      "&id=eq." + encode(weekId) + "&limit=1", "GET"
  ));
  if (!rows.length) throw new CheckoutError("week_not_found", 404);
}
export async function shoppingContext(ctx: ShoppingService, weekId: string) {
  await checkedWeek(ctx, weekId);
  const house = encode(ctx.householdId);
  const [defaults, overrides] = await Promise.all([
    ctx.admin(
      "/rest/v1/ingredient_bilka_defaults?select=ingredient_id,product_id,package_base_amount_override" +
        "&household_id=eq." + house + "&limit=1000", "GET"
    ),
    ctx.admin(
      "/rest/v1/shopping_product_overrides?select=ingredient_id,product_id,manual_product_name," +
        "package_count,purchased_amount_base,paid_total_ore" +
        "&household_id=eq." + house +
        "&weekly_plan_id=eq." + encode(weekId) + "&limit=1000", "GET"
    ),
  ]);
  return { defaults: list(defaults), overrides: list(overrides) };
}
export interface CheckoutPayload {
  weekId: string;
  ingredientId: string;
  productId?: string | null;
  manualProductName?: string | null;
  packageCount?: number | null;
  amountBase: number;
  paidTotalOre?: number | null;
}
type ProductRow = {
  id: string; package_base_amount: number | null;
  quantity_kind: string | null; price_ore: number | null;
};

/**
 * Only the explicitly purchased product/amount is applied to pantry.
 * A transaction guarded by bilka_record_purchase rejects repeated adds.
 */
export async function recordPurchase(ctx: ShoppingService, input: CheckoutPayload) {
  await checkedWeek(ctx, input.weekId);
  if (!uuid(input.ingredientId)) throw new CheckoutError("invalid_ingredient", 400);
  const house = encode(ctx.householdId);
  const ingredientRows = list<{id: string; quantity_type: string}>(await ctx.admin(
    "/rest/v1/ingredients?select=id,quantity_type&household_id=eq." + house +
      "&id=eq." + encode(input.ingredientId) + "&limit=1", "GET"
  ));
  if (!ingredientRows.length) throw new CheckoutError("invalid_ingredient", 400);

  const productId = input.productId ?? null;
  const manualName = input.manualProductName?.trim() || null;
  if ((productId === null) === (manualName === null) ||
    (manualName !== null && (manualName.length < 2 || manualName.length > 160))) {
    throw new CheckoutError("invalid_choice", 400);
  }
  if (!Number.isFinite(input.amountBase) || input.amountBase <= 0 || input.amountBase > 1e7) {
    throw new CheckoutError("invalid_amount", 400);
  }
  if (input.paidTotalOre != null && (!Number.isSafeInteger(input.paidTotalOre) ||
      input.paidTotalOre < 0 || input.paidTotalOre > 1e9)) {
    throw new CheckoutError("invalid_cost", 400);
  }

  let packages: number | null = null;
  if (productId != null) {
    if (!onlyNumber(productId) ||
        !Number.isInteger(input.packageCount) ||
        Number(input.packageCount) < 1 || Number(input.packageCount) > 500) {
      throw new CheckoutError("invalid_packages", 400);
    }
    packages = Number(input.packageCount);
    const defaults = list<{product_id: string; package_base_amount_override: number | null}>(
      await ctx.admin("/rest/v1/ingredient_bilka_defaults?select=product_id,package_base_amount_override" +
        "&household_id=eq." + house + "&ingredient_id=eq." + input.ingredientId, "GET")
    );
    const candidates = list<{product_id: string}>(await ctx.admin(
      "/rest/v1/ingredient_bilka_candidates?select=product_id&household_id=eq." + house +
      "&ingredient_id=eq." + input.ingredientId + "&product_id=eq." + productId +
      "&match_status=eq.SUGGESTED&limit=1", "GET"
    ));
    if (!defaults.some((x) => x.product_id === productId) &&
        !candidates.some((x) => x.product_id === productId)) {
      throw new CheckoutError("not_household_product", 403);
    }
    const products = list<ProductRow>(await ctx.admin(
      "/rest/v1/bilka_products?select=id,package_base_amount,quantity_kind,price_ore" +
        "&id=eq." + productId + "&chain_slug=eq.bilka&limit=1", "GET"
    ));
    const product = products[0];
    if (!product) throw new CheckoutError("not_bilka_product", 400);
    if (product.quantity_kind !== null && product.quantity_kind !== ingredientRows[0].quantity_type) {
      throw new CheckoutError("incompatible_package_units", 400);
    }
    const override = defaults.find((x) => x.product_id === productId)?.package_base_amount_override;
    const packageBase = override ?? product.package_base_amount;
    if (packageBase != null) {
      const expected = packageBase * packages;
      const tolerance = Math.max(0.0001, expected * 1e-7);
      if (Math.abs(input.amountBase - expected) > tolerance) {
        throw new CheckoutError("package_amount_mismatch", 400);
      }
    }
    // Provider may not know a pack size: explicit manual amount is still allowed.
  } else if (input.packageCount != null) {
    // Manually named goods can also have a counted set of packs.
    if (!Number.isInteger(input.packageCount) || input.packageCount < 1 || input.packageCount > 500) {
      throw new CheckoutError("invalid_packages", 400);
    }
    packages = input.packageCount;
  }

  try {
    const amount = await ctx.rpc("bilka_record_purchase", {
      p_household_id: ctx.householdId,
      p_weekly_plan_id: input.weekId,
      p_ingredient_id: input.ingredientId,
      p_product_id: productId,
      p_manual_product_name: manualName,
      p_package_count: packages,
      p_purchased_amount_base: input.amountBase,
      p_paid_total_ore: input.paidTotalOre ?? null,
    });
    return { saved: true, purchasedAmountBase: Number(amount), productId };
  } catch {
    // SQL errors may contain internal details; never include them in user responses.
    throw new CheckoutError("purchase_not_saved_or_already_recorded", 409);
  }
}
