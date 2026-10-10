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
          id: ingredient, name: "Mel", never_buy: false
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
      assert(String(url).includes("chain=bilka"), "Only Bilka search");
      assert(String(url).includes("grocery=1"), "Search must exclude non-grocery categories");
      return Response.json({
        products: [
          { id: 333, name: "Hvedemel 2 kg", brand: "Eksempelmærke", category_name: "Kolonial",
            prices: [{ chain_slug: "bilka", price: 2000, unit_price: 1000 }] },
          { id: 335, name: "Hvedemel 1 kg", category_name: "Kolonial",
            prices: [{ chain_slug: "bilkatogo", price: 800 }] },
          { id: 336, name: "Hvedemel kropsskrub",
            category_name: "Personlig pleje",
            prices: [{ chain_slug: "bilka", price: 1400 }] },
          { id: 337, name: "Hvedemel hundefoder",
            category_name: "Dyremad",
            prices: [{ chain_slug: "bilka", price: 1200 }] }
        ],
        total: 4
      });
    }
  };
  const first = await manualProductSearch(ctx, ingredient, "hvedemel");
  assert(first.count === 1, "Exclude BilkaToGo and non-food products");
  assert(!first.fromCache, "First call is fresh");
  assert(realRequests === 1, "Exactly one Prej call");
  assert(candidateRows.length === 1, "Only valid product suggested");
  assert(seen.includes("prej_reserve_call"), "Must reserve quota");
  assert(seen.every((p) => !p.includes("/ingredient_bilka_defaults")),
    "Never auto-write defaults");

  const second = await manualProductSearch(ctx, ingredient, "hvedemel");
  assert(second.fromCache, "Second call from cache");
  assert(second.count === 1, "Cached results must be relevance-checked too");
  assert(realRequests === 1, "Second search must use zero Prej calls");
  assert((cache[0] as {query_key:string}).query_key.startsWith("q:bilka:grocery1:food2:"), "No reuse of unfiltered search cache");
});

Deno.test("manual search refuses Flødebolle and flødelikør as Fløde", async () => {
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
            prices: [{ chain_slug: "bilka", price: 6495 }] },
          { id: 135282, name: "SNCK Proteinbar Flødebolle", category_name: "Slik & snacks",
            prices: [{ chain_slug: "bilka", price: 2200 }] },
          { id: 100101, name: "Arla Piskefløde 38%", category_name: "Mejeri & køl",
            prices: [{ chain_slug: "bilka", price: 1895 }] },
        ],
      });
    },
  };
  const first = await manualProductSearch(ctx, ingredient, "fløde");
  assert(first.count === 1, "Only actual whipping cream is relevant");
  assert(candidates.length === 1, "No non-cream candidates");
  assert(candidates[0].product_id === "100101", "Expected piskefløde");
  assert(candidates[0].match_score !== null, "Ranked by ingredient match");
  const second = await manualProductSearch(ctx, ingredient, "fløde");
  assert(second.count === 1 && second.fromCache, "Cache path must be relevance-filtered");
  assert(providerCalls === 1, "No extra Prej call for cached search");
  assert(candidates.every((c) => c.product_id === "100101"),
    "Cache cannot reintroduce invalid suggestions");
});
