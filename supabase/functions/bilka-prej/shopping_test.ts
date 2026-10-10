import { recordPurchase, shoppingContext, CheckoutError } from "./shopping_server.ts";
import { ALLOWED_BILKA_RPCS } from "./rpc_allowlist.ts";

const householdId = "43d180fe-eef9-48c0-bd28-b6f0f9c88391";
const weekId = "2a8bf4c0-55f4-49d5-bba0-4ef76836f56b";
const ingredientId = "50956376-a03a-4e1e-bef4-a8708b5ad04e";
function assertTrue(v: boolean, message: string) { if (!v) throw new Error(message); }
function equal(a: unknown, b: unknown) {
  if (JSON.stringify(a) !== JSON.stringify(b))
    throw new Error("Expected " + JSON.stringify(b) + " got " + JSON.stringify(a));
}

function backend(opts: { selected?: string; match?: string; kind?: string | null; neverBuy?: boolean } = {}) {
  const methods: string[] = [];
  const purchases: Array<Record<string, unknown>> = [];
  const defaults = [{ ingredient_id: ingredientId, product_id: opts.selected ?? "42",
    package_base_amount_override: null }];
  const shopping = {
    householdId,
    rpc: async (name: string, args: Record<string, unknown>): Promise<unknown> => {
      methods.push("RPC " + name);
      if (name !== "bilka_record_purchase") throw new Error("unexpected RPC");
      purchases.push(args);
      return args.p_purchased_amount_base;
    },
    admin: async (path: string, method: "GET" | "POST"): Promise<unknown> => {
      methods.push(method + " " + path);
      if (method !== "GET") throw new Error("Checkout must write only through atomic RPC");
      if (path.includes("/weekly_plans?")) return [{ id: weekId }];
      if (path.includes("/ingredients?")) return [{ id: ingredientId, quantity_type: "MASS", never_buy: opts.neverBuy ?? false }];
      if (path.includes("/ingredient_bilka_defaults?")) return defaults;
      if (path.includes("/ingredient_bilka_candidates?")) {
        return opts.match ? [{ product_id: opts.match }] : [];
      }
      if (path.includes("/bilka_products?")) return [{
        id: "42", package_base_amount: 1000,
        quantity_kind: opts.kind === undefined ? "MASS" : opts.kind, price_ore: 1800,
      }];
      if (path.includes("/shopping_product_overrides?")) return [];
      throw new Error("Unexpected path " + path);
    },
  };
  return { shopping, methods, purchases };
}

Deno.test("checkout reads only the current household and week", async () => {
  const { shopping, methods } = backend();
  const result = await shoppingContext(shopping, weekId);
  equal(result.defaults.length, 1);
  equal(result.overrides.length, 0);
  assertTrue(methods.some((x) => x.includes("household_id=eq." + householdId)), "Household filter missing");
  assertTrue(methods.some((x) => x.includes("weekly_plan_id=eq." + weekId)), "Week filter missing");
});

Deno.test("two full bags are recorded as two kilograms, not the 1.2kg shortage", async () => {
  const { shopping, purchases, methods } = backend();
  const result = await recordPurchase(shopping, {
    weekId, ingredientId, productId: "42", packageCount: 2,
    amountBase: 2000, paidTotalOre: 3800,
  });
  equal(result.saved, true);
  equal(result.purchasedAmountBase, 2000);
  equal(purchases.length, 1);
  equal(purchases[0].p_product_id, "42");
  equal(purchases[0].p_package_count, 2);
  equal(purchases[0].p_paid_total_ore, 3800);
  assertTrue(methods.every((m) => !m.startsWith("POST /rest/v1/ingredient_bilka_defaults")),
    "One-time shopping purchase must not change permanent default");
});

Deno.test("one-off alternative candidate is accepted but permanent default unchanged", async () => {
  const { shopping, purchases } = backend({ selected: "99", match: "42" });
  await recordPurchase(shopping, { weekId, ingredientId, productId: "42",
    packageCount: 1, amountBase: 1000 });
  equal(purchases[0].p_product_id, "42");
});

Deno.test("wrong pack size and mismatched quantity types are rejected", async () => {
  const { shopping } = backend();
  let failure = "";
  try {
    await recordPurchase(shopping, { weekId, ingredientId,
      productId: "42", packageCount: 2, amountBase: 1200 });
  } catch (e) { failure = (e as CheckoutError).code; }
  equal(failure, "package_amount_mismatch");

  const wrongKind = backend({ kind: "VOLUME" });
  failure = "";
  try {
    await recordPurchase(wrongKind.shopping, { weekId, ingredientId,
      productId: "42", packageCount: 2, amountBase: 2000 });
  } catch (e) { failure = (e as CheckoutError).code; }
  equal(failure, "incompatible_package_units");
});

Deno.test("manually named one-off item is permitted with explicit quantity", async () => {
  const { shopping, purchases } = backend();
  await recordPurchase(shopping, { weekId, ingredientId, manualProductName: "Andet hvedemel",
    amountBase: 1500, paidTotalOre: 2500 });
  equal(purchases[0].p_product_id, null);
  equal(purchases[0].p_manual_product_name, "Andet hvedemel");
  equal(purchases[0].p_purchased_amount_base, 1500);
});

Deno.test("duplicate attempt returns a controlled error without adding stock", async () => {
  const x = backend();
  x.shopping.rpc = async (): Promise<unknown> => { throw new Error("SQL already recorded"); };
  let reason = "";
  try {
    await recordPurchase(x.shopping, { weekId, ingredientId, productId: "42",
      packageCount: 1, amountBase: 1000 });
  } catch (e) { reason = (e as CheckoutError).code; }
  equal(reason, "purchase_not_saved_or_already_recorded");
});


Deno.test("UUID validation accepts the canonical five groups and rejects malformed identifiers", async () => {
  const correct = backend();
  const validWeek = await shoppingContext(correct.shopping, weekId);
  equal(validWeek.defaults.length, 1);
  assertTrue(correct.methods.some((method) => method.includes("&id=eq." + weekId)),
    "A valid UUID must reach the household-scoped week lookup");

  const invalid = backend();
  let error = "";
  try {
    await shoppingContext(invalid.shopping, "2a8bf4c0-55f4-49d5-bba0-4ef76836f56b-extra");
  } catch (e) {
    error = (e as CheckoutError).code;
  }
  equal(error, "invalid_week");
  equal(invalid.methods, []); // Reject before any database access.

  let ingredientError = "";
  try {
    await recordPurchase(invalid.shopping, {
      weekId,
      ingredientId: "invalid-ingredient",
      manualProductName: "Hvedemel",
      amountBase: 1000,
    });
  } catch (e) {
    ingredientError = (e as CheckoutError).code;
  }
  equal(ingredientError, "invalid_ingredient");
});

Deno.test("atomic purchase RPC is allowed, arbitrary service RPCs stay denied", () => {
  equal(ALLOWED_BILKA_RPCS.has("bilka_record_purchase"), true);
  equal(ALLOWED_BILKA_RPCS.has("bilka_update_prices_only"), false);
  equal(ALLOWED_BILKA_RPCS.has("pg_read_file"), false);
});

Deno.test("never-buy ingredients cannot be bought via the Edge Function", async () => {
  const { shopping, purchases } = backend({ neverBuy: true });
  let result = "";
  try {
    await recordPurchase(shopping, {
      weekId, ingredientId, manualProductName: "Vand",
      amountBase: 500,
    });
  } catch (e) {
    result = (e as CheckoutError).code;
  }
  equal(result, "invalid_ingredient");
  equal(purchases.length, 0);
});

Deno.test("unknown Prej quantity unit permits manually verified purchased amount", async () => {
  const { shopping, purchases } = backend({ kind: null });
  await recordPurchase(shopping, {
    weekId, ingredientId, productId: "42",
    packageCount: 2, amountBase: 1250,
  });
  equal(purchases.length, 1);
  equal(purchases[0].p_purchased_amount_base, 1250);
});
