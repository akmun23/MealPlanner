import { priceOnlyUpdates } from "./price_updates.ts";
import { ALLOWED_BILKA_RPCS } from "./rpc_allowlist.ts";

function equal(actual: unknown, expected: unknown) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error("Expected " + JSON.stringify(expected) + ", received " + JSON.stringify(actual));
  }
}

Deno.test("refresh preserves exact selected product even if Prej changes brand, size or photo", () => {
  const updates = priceOnlyUpdates([{
    id: 42,
    name: "Changed name",
    brand: "Different brand",
    image_url: "https://provider.example.com/replacement-full-resolution.jpg",
    quantity: 9,
    unit: "kg",
    prices: [
      { chain_slug: "bilkatogo", price: 600, quantity: 6, unit: "kg" },
      { chain_slug: "bilka", price: 2095, unit_price: 1047,
        unit_price_label: "kr/kg", last_seen_date: "2026-10-10", is_offer: true }
    ],
  }], ["42"]);
  equal(updates, [{
    id: "42", price_ore: 2095, unit_price_ore: 1047,
    unit_price_basis: "KG", is_offer: true,
    price_updated_at: "2026-10-10T12:00:00Z"
  }]);
  equal(Object.keys(updates[0]).sort(), [
    "id", "is_offer", "price_ore", "price_updated_at",
    "unit_price_basis", "unit_price_ore"
  ]);
  equal(ALLOWED_BILKA_RPCS.has("bilka_update_prices_only"), true);
});

Deno.test("manual refresh does not import unrelated products or BilkaToGo prices", () => {
  const rows = priceOnlyUpdates([
    { id: 43, name: "Another product", prices: [{ chain_slug: "bilka", price: 3200 }] },
    { id: 42, name: "Only BilkaToGo", prices: [{ chain_slug: "bilkatogo", price: 1000 }] },
    { id: 44, name: "Selected SKU", prices: [{ chain_slug: "bilka", price: 1000 }] },
  ], ["42", "44"]);
  equal(rows.length, 1);
  equal(rows[0].id, "44");
  equal(rows[0].price_ore, 1000);
});

Deno.test("invalid negative and duplicate provider prices do not overwrite selected defaults", () => {
  const rows = priceOnlyUpdates([
    { id: 22, name: "Bad price", prices: [{ chain_slug: "bilka", price: -1200 }] },
    { id: 23, name: "Good", prices: [{ chain_slug: "bilka", price: 1234 }] },
    { id: 23, name: "Good repeat", prices: [{ chain_slug: "bilka", price: 1244 }] },
  ], ["22","23"]);
  equal(rows.length, 1);
  equal(rows[0].price_ore, 1244);
});
