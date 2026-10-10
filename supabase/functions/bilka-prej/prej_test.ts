import { batchIds, dailyRemaining, toBilkaCacheRow, validPrejKey } from "./prej.ts";

function eq(actual: unknown, expected: unknown) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error("Expected " + JSON.stringify(expected) + " but got " + JSON.stringify(actual));
  }
}

Deno.test("batching 1,003 distinct product IDs takes three calls", () => {
  const chunks = batchIds(Array.from({ length: 1003 }, (_, i) => String(i + 1)));
  eq(chunks.map((a) => a.length), [500, 500, 3]);
  eq(batchIds(["9", "9", "11", "hello"]), [["9", "11"]]);
});

Deno.test("only Bilka prices, never BilkaToGo or product-level cheapest price", () => {
  const p = {
    id: 42,
    name: "Hvedemel",
    brand: "Valsemøllen",
    image_url: "https://image.example/42.jpg",
    quantity: 1,
    unit: "kg",
    min_price: 1095,
    prices: [
      { chain_slug: "bilkatogo", price: 1095, quantity: 1, unit: "kg" },
      {
        chain_slug: "bilka", price: 2050, quantity: 2, unit: "kg",
        unit_price: 1025, unit_price_label: "10,25 kr/kg",
        is_offer: true, last_seen_date: "2026-10-10",
      },
    ],
  };
  const row = toBilkaCacheRow(p);
  eq(row?.price_ore, 2050);
  eq(row?.unit_price_ore, 1025);
  eq(row?.package_base_amount, 2000);
  eq(row?.brand, "Valsemøllen");
  eq(row?.image_url, "https://image.example/42.jpg");
  eq(row?.is_offer, true);
  eq(row?.price_updated_at, "2026-10-10T12:00:00Z");
  eq(row?.chain_slug, "bilka");
});

Deno.test("products without Bilka price are excluded", () => {
  eq(toBilkaCacheRow({
    id: 5, name: "Kartofler", prices: [{ chain_slug: "bilkatogo", price: 1200 }],
  }), null);
});

Deno.test("fallback image, missing brand and unit normalize safely", () => {
  const row = toBilkaCacheRow({
    id: 42, name: "Ukendt vare", image_url: "javascript:alert(1)",
    prices: [{ chain_slug: "bilka", price: 1200 }],
  });
  eq(row?.brand, null);
  eq(row?.image_url, null);
  eq(row?.package_base_amount, null);
  eq(row?.price_ore, 1200);
});

Deno.test("remaining daily quota header and user key validation", () => {
  eq(dailyRemaining(new Headers({ "X-RateLimit-Remaining-Day": "19" })), 19);
  eq(dailyRemaining(new Headers()), null);
  eq(dailyRemaining(new Headers({ "X-RateLimit-Remaining-Day": "-1" })), null);
  eq(validPrejKey("prej_dev_a1b2c3d4e5f6g7h8"), true);
  eq(validPrejKey("prej_dev_short"), false);
  eq(validPrejKey("some-other-token"), false);
});
