import {
  barcodeProductSearch, eanCacheRowsForProducts, exactBilkaToGoBarcode, parseScannedProduct, validGtin,
} from "./barcode_server.ts";

const householdId = "ce85a721-78a3-40c5-a18d-13b1011ba54b";
const ingredientId = "0a132cc4-7633-40d3-b317-47b2a99192fe";
const ean = "4006381333931";
function assert(v: boolean, reason: string) {
  if (!v) throw new Error(reason);
}
function example(prices?: unknown) {
  return {
    id: 12345, name: "Økologisk paprika 40 g", brand: "Testmærke",
    prices: prices ?? [{
      chain_slug: "bilkatogo", price: 1895,
      chain_gtins: [ean],
      quantity: 40, unit: "g",
    }],
  };
}

Deno.test("validates GTIN checksums and accepts 8, 12, 13 and 14 digits", () => {
  assert(validGtin(ean), "EAN-13 must pass");
  assert(validGtin("96385074"), "EAN-8 must pass");
  assert(validGtin("036000291452"), "UPC-A must pass");
  assert(validGtin("04006381333931"), "GTIN-14 must pass");
  assert(!validGtin("4006381333932"), "Invalid check digit accepted");
  assert(!validGtin("123456"), "Too-short number accepted");
  assert(!validGtin("400638133393A"), "Non-numeric barcode accepted");
  assert(!validGtin("000000000000000"), "Too-long GTIN accepted");
});

Deno.test("only retailer-owned barcode variants match BilkaToGo prices", () => {
  assert(exactBilkaToGoBarcode(example(), ean), "Exact ToGo barcode should match");
  assert(!exactBilkaToGoBarcode(
    example([{ chain_slug: "bilka", price: 100, chain_gtins: [ean] }]), ean,
  ), "Bilka's EAN must not count as ToGo");
  assert(!exactBilkaToGoBarcode(
    example([{ chain_slug: "bilkatogo", price: 100, chain_gtins: ["96385074"] }]), ean,
  ), "Another retail variant must not match");
  assert(!exactBilkaToGoBarcode(
    example([{ chain_slug: "bilkatogo", price: 100 }]), ean,
  ), "Unpublished retailer EAN must not be inferred");
  assert(parseScannedProduct({product: example()})?.id === 12345,
    "Envelope product parsing failed");
  assert(parseScannedProduct(example())?.id === 12345,
    "Plain product parsing failed");
});

Deno.test("normal name search pre-seeds many EANs from exact retailer GTINs without an API request", () => {
  const rows = eanCacheRowsForProducts([
    example(), example([{ chain_slug:"bilka", price: 150, chain_gtins: ["96385074"] }]),
    {...example(), id: 54321, prices: [{
      chain_slug: "bilkatogo", price: 995, chain_gtins: ["96385074"]
    }]},
    {...example(), id: 22222, prices: [{
      chain_slug: "bilkatogo", price: 995, chain_gtins: ["96385074"]
    }]},
  ], 0);
  assert(rows.length === 2, "Only unique priced ToGo EANs should be seeded");
  assert(rows.some((r) => r.query_key.endsWith("04006381333931") &&
    r.product_ids[0] === "bilkatogo:12345"), "EAN-13 must be canonicalized to GTIN-14");
  assert(rows.some((r) => r.query_key.endsWith("00000096385074") &&
    r.product_ids[0] === "bilkatogo:54321"), "Matching must use retailer price GTIN");
});

Deno.test("first GTIN scan spends one Prej call, reuse across ingredients costs zero", async () => {
  const cache: Array<Record<string, unknown>> = [];
  const products: Array<Record<string, unknown>> = [];
  const candidates: Array<Record<string, unknown>> = [];
  const jobs: string[] = [];
  const secondIngredient = "11111111-1111-4111-8111-111111111111";
  let providerCalls = 0;
  let reservedCalls = 0;
  let completedCalls = 0;
  const ctx = {
    householdId,
    userId: "personal-user",
    admin: async (path: string, method: "GET" | "POST", body?: unknown): Promise<unknown> => {
      if (method === "GET") {
        if (path.includes("/ingredients?")) return [{id: ingredientId, never_buy: false}];
        if (path.includes("/bilka_search_cache?")) return cache;
        if (path.includes("/bilka_products?")) return products;
        throw Error("Unexpected read " + path);
      }
      const rows = body as Array<Record<string, unknown>>;
      jobs.push(path);
      if (path.includes("/bilka_search_cache?")) cache.push(...rows);
      if (path.includes("/bilka_products?")) products.push(...rows);
      if (path.includes("/ingredient_bilka_candidates?")) candidates.push(...rows);
      return rows;
    },
    rpc: async (name: string): Promise<unknown> => {
      if (name === "prej_read_key") return "fake-test-key";
      if (name === "prej_reserve_call") { reservedCalls++; return true; }
      if (name === "prej_complete_call") { completedCalls++; return null; }
      throw Error("Unexpected rpc " + name);
    },
    request: async (url: string | URL | Request): Promise<Response> => {
      providerCalls++;
      assert(String(url) === "https://api.prej.app/v1/scan/" + ean, "Wrong scan URL");
      return Response.json({product: example()});
    },
  };
  const first = await barcodeProductSearch(ctx, ingredientId, ean);
  assert(first.found && !first.fromCache, "First scan should find price");
  assert(first.productId === "bilkatogo:12345", "SKU must be chain scoped");
  assert(providerCalls === 1 && reservedCalls === 1 && completedCalls === 1,
    "Provider call must be reserved and completed exactly once");

  // Different ingredient may reuse the shared cache; both retain their own candidates.
  const second = await barcodeProductSearch(ctx, secondIngredient, ean);
  assert(second.fromCache && second.found, "Household-shared cached scan must work");
  assert(providerCalls === 1 && reservedCalls === 1, "Cached EAN must cost 0");
  assert(candidates.length === 2, "Both ingredients need separate suggestions");
  assert(jobs.every((p) => !p.includes("ingredient_bilka_defaults")),
    "Scanner may not auto-pick any SKU");
});

Deno.test("unknown or mismatched EAN is negatively cached without saving a false match", async () => {
  for (const response of [
    new Response(null, { status: 404 }),
    Response.json({product: example([{chain_slug:"bilka",price:300,chain_gtins:[ean]}])}),
    Response.json({product: example([{chain_slug:"bilkatogo",price:300,chain_gtins:["96385074"]}])}),
  ]) {
    const cache: Array<Record<string, unknown>> = [];
    const writes: string[] = [];
    let requests = 0;
    const ctx = {
      householdId, userId: "owner",
      admin: async (path: string, method: "GET" | "POST", body?: unknown): Promise<unknown> => {
        if (method === "GET") {
          if (path.includes("/ingredients?")) return [{id: ingredientId, never_buy: false}];
          if (path.includes("/bilka_search_cache?")) return cache;
          if (path.includes("/bilka_products?")) return [];
          throw Error("Unexpected GET");
        }
        writes.push(path);
        if (path.includes("/bilka_search_cache?")) cache.push(...body as Array<Record<string, unknown>>);
        return [];
      },
      rpc: async (name: string): Promise<unknown> =>
        name === "prej_read_key" ? "fake-key" : true,
      request: async (): Promise<Response> => {
        requests++;
        return response.clone();
      },
    };
    const r1 = await barcodeProductSearch(ctx, ingredientId, ean);
    const r2 = await barcodeProductSearch(ctx, ingredientId, ean);
    assert(!r1.found && !r2.found && r2.fromCache, "Negative result must be cached");
    assert(requests === 1, "Negative cache should avoid repeat API cost");
    assert(writes.every((p) => !p.includes("ingredient_bilka_candidates") &&
      !p.includes("/bilka_products")), "Mismatched chain must never become a candidate");
  }
});

Deno.test("invalid GTIN is rejected without a provider request or quota reservation", async () => {
  let calls = 0;
  let rejected = false;
  try {
    await barcodeProductSearch({
      householdId, userId: "owner",
      admin: async () => { calls++; return []; },
      rpc: async () => { calls++; return true; },
      request: async () => { calls++; return Response.json({}); },
    }, ingredientId, "4006381333932");
  } catch (err) {
    rejected = err instanceof Error && err.message === "invalid_gtin";
  }
  assert(rejected && calls === 0, "Reject locally before any database/provider work");
});
