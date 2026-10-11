import { manualProductSearch } from "./manual_search_server.ts";

const house = "ce85a721-78a3-40c5-a18d-13b1011ba54b";
const ingredient = "0a132cc4-7633-40d3-b317-47b2a99192fe";

function assert(value: boolean, description: string) {
  if (!value) throw new Error(description);
}

Deno.test("manual product search reserves one user call and never changes defaults", async () => {
  const productRows: unknown[] = [];
  const candidateRows: unknown[] = [];
  const jobRows: unknown[] = [];
  const cache: unknown[] = [];
  const seen: string[] = [];
  let realRequests = 0;
  const ctx = {
    householdId: house,
    userId: "one-user",
    admin: async (path: string, method: "GET" | "POST", body?: unknown): Promise<unknown> => {
      if (method === "GET") {
        if (path.includes("/ingredients?")) return [{
          id: ingredient, name: "Hvedemel", never_buy: false
        }];
        if (path.includes("/bilka_search_cache?")) return cache;
        if (path.includes("/bilka_products?")) return productRows;
        throw new Error("Unexpected GET " + path);
      }
      seen.push(path);
      const rows = body as unknown[];
      if (path.includes("bilka_search_cache")) cache.push(...rows);
      if (path.includes("/bilka_products")) productRows.push(...rows);
      if (path.includes("/ingredient_bilka_candidates")) candidateRows.push(...rows);
      if (path.includes("/bilka_discovery_jobs")) jobRows.push(...rows);
      return rows;
    },
    rpc: async (name: string): Promise<unknown> => {
      seen.push(name);
      if (name === "prej_read_key") return "test-redacted-key";
      if (name === "prej_reserve_call") return true;
      return null;
    },
    request: async (url: string | URL | Request): Promise<Response> => {
      realRequests++;
      assert(new URL(String(url)).searchParams.get("chain") === "bilkatogo", "Use ToGo grocery source");
      assert(!new URL(String(url)).searchParams.has("grocery"), "Do not drop uncategorised ToGo products");
      return Response.json({
        products: [
          { id: 333, name: "Hvedemel 2 kg", brand: "Eksempelmærke", category_name: "Kolonial",
            prices: [{ chain_slug: "bilkatogo", price: 2000, unit_price: 1000 }] },
          { id: 335, name: "Hvedemel 1 kg", category_name: "Kolonial",
            prices: [{ chain_slug: "bilka", price: 800 }] },
          { id: 336, name: "Hvedemel kropsskrub",
            category_name: "Personlig pleje",
            prices: [{ chain_slug: "bilkatogo", price: 1400 }] },
          { id: 337, name: "Hvedemel hundefoder",
            category_name: "Dyremad",
            prices: [{ chain_slug: "bilkatogo", price: 1200 }] }
        ],
        total: 4
      });
    }
  };
  const first = await manualProductSearch(ctx, ingredient, "hvedemel");
  assert(first.count === 4, "Show all provider items, even ones without a ToGo price");
  assert(!first.fromCache, "First call is fresh");
  assert(realRequests === 1, "Exactly one Prej call");
  assert(candidateRows.length === 4, "No provider result hidden by relevance or missing price");
  assert(seen.includes("prej_reserve_call"), "Must reserve quota");
  assert(candidateRows.some((x) =>
    (x as {product_id: string}).product_id === "bilkatogo:335"),
    "Bilka-only returned products must remain visible as unpriced placeholders");
  assert(productRows.some((x) => {
    const p = x as {id:string,price_ore:number|null,is_available:boolean};
    return p.id === "bilkatogo:335" && p.price_ore === null && p.is_available === false;
  }), "Never invent ToGo prices for missing-price items");
  assert(seen.every((p) => !p.includes("/ingredient_bilka_defaults")),
    "Never auto-write defaults");

  const second = await manualProductSearch(ctx, ingredient, "hvedemel");
  assert(second.fromCache, "Second call from cache");
  assert(second.count === 4, "Cached results also show all raw items");
  assert(realRequests === 1, "Second search must use zero Prej calls");
  assert((cache[0] as {query_key:string}).query_key.startsWith("q:bilkatogo:raw4:"), "Raw queries have their own source-scoped cache");
});

Deno.test("raw manual search includes even Flødebolle and flødelikør for user review", async () => {
  const savedProducts: unknown[] = [];
  const candidates: Array<Record<string, unknown>> = [];
  const caches: Array<Record<string, unknown>> = [];
  let providerCalls = 0;
  const ctx = {
    householdId: house,
    userId: "one-user",
    admin: async (path: string, method: "GET" | "POST", body?: unknown): Promise<unknown> => {
      if (method === "GET") {
        if (path.includes("/ingredients?")) return [
          { id: ingredient, name: "Fløde", never_buy: false },
        ];
        if (path.includes("/bilka_search_cache?")) return caches;
        if (path.includes("/bilka_products?")) return savedProducts;
        throw new Error("Unexpected GET " + path);
      }
      const data = body as Array<Record<string, unknown>>;
      if (path.includes("/bilka_products?")) savedProducts.push(...data);
      if (path.includes("/bilka_search_cache?")) caches.push(...data);
      if (path.includes("/ingredient_bilka_candidates?")) candidates.push(...data);
      return data;
    },
    rpc: async (name: string): Promise<unknown> =>
      name === "prej_read_key" ? "fake-key" : true,
    request: async (): Promise<Response> => {
      providerCalls++;
      return Response.json({
        total: 3, products: [
          { id: 87649, name: "Holiday Flødelikør 17%", category_name: "Drikkevarer",
            prices: [{ chain_slug: "bilkatogo", price: 6495 }] },
          { id: 135282, name: "SNCK Proteinbar Flødebolle", category_name: "Slik & snacks",
            prices: [{ chain_slug: "bilkatogo", price: 2200 }] },
          { id: 100101, name: "Arla Piskefløde 38%", category_name: "Mejeri & køl",
            prices: [{ chain_slug: "bilkatogo", price: 1895 }] },
        ],
      });
    },
  };
  const first = await manualProductSearch(ctx, ingredient, "fløde");
  assert(first.count === 3, "User can inspect all results, including poor ones");
  assert(candidates.length === 3, "All ToGo products shown without hidden filtering");
  assert(candidates.some((c) => c.product_id === "bilkatogo:100101"), "Actual cream visible");
  assert(candidates.every((c) => c.match_score === null), "No unsupported relevance claims");
  const second = await manualProductSearch(ctx, ingredient, "fløde");
  assert(second.count === 3 && second.fromCache, "Cache retains all raw results");
  assert(providerCalls === 1, "No extra Prej call for cached search");
  assert(candidates.some((c) => c.product_id === "bilkatogo:87649"),
    "Cache keeps even irrelevant results for explicit user review");
});

Deno.test("manual pagination retains every result in page-specific cache", async () => {
  const products: Array<Record<string, unknown>> = [];
  const candidates: Array<Record<string, unknown>> = [];
  const cached: Array<Record<string, unknown>> = [];
  let called = 0;
  const ctx = {
    householdId: house,
    userId: "owner",
    admin: async (path: string, method: "GET" | "POST", body?: unknown): Promise<unknown> => {
      if (method === "GET") {
        if (path.includes("/ingredients?"))
          return [{ id: ingredient, name: "Agurk", never_buy: false }];
        if (path.includes("/bilka_search_cache?"))
          return cached.filter((x) => String(x.query_key) ===
            decodeURIComponent(path.split("&query_key=eq.")[1].split("&")[0]));
        if (path.includes("/bilka_products?")) return products;
        throw new Error("Unexpected read " + path);
      }
      const rows = body as Array<Record<string, unknown>>;
      if (path.includes("/bilka_products?")) products.push(...rows);
      if (path.includes("/bilka_search_cache?")) cached.push(...rows);
      if (path.includes("/ingredient_bilka_candidates?")) candidates.push(...rows);
      return rows;
    },
    rpc: async (name: string): Promise<unknown> =>
      name === "prej_read_key" ? "fake-key" : true,
    request: async (url: string | URL | Request): Promise<Response> => {
      called++;
      const page = Number(new URL(String(url)).searchParams.get("page"));
      const items = Array.from({ length: page === 1 ? 100 : 1 }, (_, i) => ({
        id: (page - 1) * 100 + i + 1,
        name: "Tilfældig vare " + i,
        prices: [{ chain_slug: "bilkatogo", price: 500 }],
      }));
      return Response.json({ total: 101, products: items });
    },
  };
  const first = await manualProductSearch(ctx, ingredient, "agurk", 1);
  assert(first.count === 100 && first.hasMore, "First page is all 100 results");
  const second = await manualProductSearch(ctx, ingredient, "agurk", 2);
  assert(second.count === 1 && !second.hasMore, "Second page contains last result");
  assert(called === 2, "Each uncached page costs one call");
  assert(candidates.length === 101, "All 101 results are visible candidates");
  const again = await manualProductSearch(ctx, ingredient, "agurk", 1);
  assert(again.fromCache && again.count === 100 && called === 2,
    "Repeated page uses free application cache");
});
