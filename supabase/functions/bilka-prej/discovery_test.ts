import { groupSearches, groupSearchesAtStage, rankCandidates, searchQueries, searchTerm, scoreCandidate } from "./discovery.ts";
import { discoverProducts } from "./discovery_server.ts";

function eq(actual: unknown, expected: unknown) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error("Expected " + JSON.stringify(expected) + ", got " + JSON.stringify(actual));
  }
}

const flour = {
  id: 14, name: "Budget Hvedemel 2 kg", brand: "Budget", category_name: "Kolonial",
  quantity: 2, unit: "kg",
  prices: [{ chain_slug: "bilka", price: 1800, unit_price: 900,
    unit_price_label: "9,00 kr/kg", last_seen_date: "2026-10-10" }],
};
const pizza = {
  id: 25, name: "Casa Italia Pizzamel Tipo 00", brand: "Casa Italia", category_name: "Kolonial",
  quantity: 1, unit: "kg",
  prices: [{ chain_slug: "bilka", price: 2500, unit_price: 2500,
    unit_price_label: "25,00 kr/kg", last_seen_date: "2026-10-10" }],
};

Deno.test("only real ingredient names are searched without inferred broader words", () => {
  const inputs = [
    { id: "one", name: "Hvedemel" },
    { id: "two", name: "Pizzamel" },
    { id: "three", name: "Kartofler" },
  ];
  const groups = groupSearches(inputs);
  eq(groups.map((g) => g.query), ["hvedemel", "kartofler", "pizzamel"]);
  eq(groupSearchesAtStage(inputs, 0).length, 3);
  eq(groupSearchesAtStage(inputs, 1), []);
  eq(searchQueries("Pizzamel"), ["pizzamel"]);
  eq(searchQueries("Rødløg"), ["rødløg"]);
  eq(searchQueries("Økologisk fuldkornscouscous"), ["økologisk fuldkornscouscous"]);
  eq(rankCandidates("Hvedemel", [flour, pizza])[0].product.id, 14);
  eq(rankCandidates("Pizzamel", [flour, pizza])[0].product.id, 25);
  eq(scoreCandidate("Tomat", flour), 0);
  eq(scoreCandidate("Basmati ris", { id: 7, name: "Salling Basmatiris 1 kg" }), 0.88);
});

Deno.test("Danish ingredient text is kept rather than broadened to another food", () => {
  eq(searchTerm("Rødløg"), "rødløg");
  eq(searchTerm("Kidneybønner"), "kidneybønner");
  eq(searchTerm("Bacontern (pakke)"), "bacontern");
  eq(searchTerm("Pizzamel"), "pizzamel");
});

Deno.test("independent exact searches produce suggestions without selecting defaults", async () => {
  const products: unknown[] = [];
  const candidates: unknown[] = [];
  const caches: unknown[] = [];
  const jobs: unknown[] = [];
  const apiCalls: string[] = [];
  const rpcCalls: string[] = [];
  const members = [
    { id: "flour-a", name: "Hvedemel", never_buy: false },
    { id: "pizza-b", name: "Pizzamel", never_buy: false },
    { id: "water-c", name: "Vand", never_buy: true },
  ];

  const ctx = {
    userId: "my-user",
    householdId: "my-household",
    admin: async (path: string, method: "GET" | "POST", body?: unknown): Promise<unknown> => {
      if (method === "GET") {
        if (path.includes("/ingredients?")) return members.filter((m) => !m.never_buy);
        if (path.includes("/ingredient_bilka_defaults?")) return [];
        if (path.includes("/ingredient_bilka_candidates?")) return candidates;
        if (path.includes("/bilka_discovery_jobs?")) return jobs;
        if (path.includes("/bilka_search_cache?")) return caches;
        if (path.includes("/bilka_products?")) return products;
        throw new Error("unexpected GET " + path);
      }
      const entries = body as Array<Record<string, unknown>>;
      if (path.includes("/bilka_products?")) products.push(...entries);
      else if (path.includes("/ingredient_bilka_candidates?")) candidates.push(...entries);
      else if (path.includes("/bilka_search_cache?")) caches.push(...entries);
      else if (path.includes("/bilka_discovery_jobs?")) {
        for (const row of entries) {
          const idx = jobs.findIndex((j) => (j as Record<string, unknown>).ingredient_id === row.ingredient_id);
          if (idx >= 0) jobs[idx] = { ...jobs[idx] as object, ...row };
          else jobs.push(row);
        }
      } else throw new Error("unexpected POST " + path);
      return entries;
    },
    rpc: async (name: string): Promise<unknown> => {
      rpcCalls.push(name);
      if (name === "prej_read_key") return "redacted-test-token";
      if (name === "prej_reserve_call") return true;
      return null;
    },
    fetchPrej: async (url: string | URL | Request): Promise<Response> => {
      apiCalls.push(String(url));
      return Response.json(
        { products: [flour, pizza], total: 2, page: 1, limit: 100 },
        { headers: { "X-RateLimit-Remaining-Day": "23" } },
      );
    },
  };
  const first = await discoverProducts(ctx);
  eq(first.requestsUsed, 2);
  eq(first.ready, 2);
  eq(first.pending, 0);
  eq(first.total, 2); // "Købes aldrig" is excluded.
  eq(first.items.map((i) => [i.name, i.state]), [
    ["Hvedemel", "READY"], ["Pizzamel", "READY"]
  ]);
  eq(apiCalls.length, 2);
  eq(apiCalls[0].includes("chain=bilka"), true);
  eq(apiCalls[0].includes("grocery=1"), true);
  eq(candidates.length, 2); // One validated SKU candidate per ingredient.
  eq(caches.length, 2);
  eq((caches[0] as {query_key:string}).query_key.startsWith("q:bilka:grocery1:food2:"), true);
  eq(apiCalls.map((url) => new URL(url).searchParams.get("q")), ["hvedemel", "pizzamel"]);
  if (!rpcCalls.includes("prej_reserve_call")) throw new Error("Quota reservation missing");

  const second = await discoverProducts(ctx);
  eq(second.requestsUsed, 0);
  eq(second.pending, 0);
  eq(apiCalls.length, 2);
});

Deno.test("unit-price order never automatically changes household defaults", () => {
  const options = rankCandidates("Hvedemel", [
    { ...flour, prices: [{ chain_slug: "bilka", price: 2700, unit_price: 1350 }] },
    { ...flour, id: 222, name: "Hvedemel 5 kg",
      prices: [{ chain_slug: "bilka", price: 3500, unit_price: 700 }] },
  ], 10);
  if (options.length !== 2) throw new Error("Missing candidates");
  // Discovery only ranks: there is no call to ingredient_bilka_defaults here.
});

Deno.test("a chosen SKU avoids further discovery and is never replaced", async () => {
  const writes: string[] = [];
  const info = await discoverProducts({
    userId: "owner",
    householdId: "house",
    admin: async (path: string, method: "GET" | "POST"): Promise<unknown> => {
      if (method === "POST") {
        writes.push(path);
        return [];
      }
      if (path.includes("/ingredients?")) return [
        { id: "preferred", name: "Hvedemel", never_buy: false },
      ];
      if (path.includes("/ingredient_bilka_defaults?")) return [
        { ingredient_id: "preferred", product_id: "50042" },
      ];
      if (path.includes("/ingredient_bilka_candidates?")) return [];
      if (path.includes("/bilka_discovery_jobs?")) return [];
      throw new Error("Unexpected GET " + path);
    },
    rpc: async () => { throw new Error("Selected product must not use Prej quota"); },
    fetchPrej: async () => { throw new Error("Selected product must not be researched"); },
  });
  eq(info.chosen, 1);
  eq(info.pending, 0);
  eq(info.requestsUsed, 0);
  eq(writes, []);
});

Deno.test("missing results do not cause invented broader searches", async () => {
  const calls: string[] = [];
  const suggestions: Array<Record<string, unknown>> = [];
  const jobs: Array<Record<string, unknown>> = [];
  const caches: Array<Record<string, unknown>> = [];
  const ctx = {
    userId: "user",
    householdId: "house",
    admin: async (path: string, method: "GET" | "POST", body?: unknown): Promise<unknown> => {
      if (method === "GET") {
        if (path.includes("/ingredients?")) return [{ id: "pizza", name: "Pizzamel", never_buy: false }];
        if (path.includes("/ingredient_bilka_defaults?")) return [];
        if (path.includes("/ingredient_bilka_candidates?")) return suggestions;
        if (path.includes("/bilka_discovery_jobs?")) return jobs;
        if (path.includes("/bilka_search_cache?")) return caches;
        if (path.includes("/bilka_products?")) return [];
        throw new Error("Unexpected GET " + path);
      }
      const rows = body as Array<Record<string, unknown>>;
      if (path.includes("/ingredient_bilka_candidates?")) suggestions.push(...rows);
      if (path.includes("/bilka_search_cache?")) caches.push(...rows);
      if (path.includes("/bilka_discovery_jobs?")) jobs.push(...rows);
      return rows;
    },
    rpc: async (name: string): Promise<unknown> =>
      name === "prej_read_key" ? "test-key" : true,
    fetchPrej: async (url: string | URL | Request): Promise<Response> => {
      const query = new URL(String(url)).searchParams.get("q")!;
      calls.push(query);
      return Response.json({ products: [], total: 0 });
    },
  };
  const result = await discoverProducts(ctx);
  eq(calls, ["pizzamel"]);
  eq(result.ready, 0);
  eq(result.noMatch, 1);
  eq(result.requestsUsed, 1);
  eq(suggestions.length, 0);
  eq(result.pending, 0);
});
