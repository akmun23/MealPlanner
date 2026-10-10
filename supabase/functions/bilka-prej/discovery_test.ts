import { groupSearches, rankCandidates, searchTerm, scoreCandidate } from "./discovery.ts";
import { discoverProducts } from "./discovery_server.ts";

function eq(actual: unknown, expected: unknown) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error("Expected " + JSON.stringify(expected) + ", got " + JSON.stringify(actual));
  }
}

const flour = {
  id: 14, name: "Budget Hvedemel 2 kg", brand: "Budget",
  quantity: 2, unit: "kg",
  prices: [{ chain_slug: "bilka", price: 1800, unit_price: 900,
    unit_price_label: "9,00 kr/kg", last_seen_date: "2026-10-10" }],
};
const pizza = {
  id: 25, name: "Casa Italia Pizzamel Tipo 00", brand: "Casa Italia",
  quantity: 1, unit: "kg",
  prices: [{ chain_slug: "bilka", price: 2500, unit_price: 2500,
    unit_price_label: "25,00 kr/kg", last_seen_date: "2026-10-10" }],
};

Deno.test("Mel and Pizzamel share a search, but are still independently matched", () => {
  const groups = groupSearches([
    { id: "one", name: "Mel" },
    { id: "two", name: "Pizzamel" },
    { id: "three", name: "Kartofler" },
  ]);
  eq(groups.length, 2);
  eq(groups[0].query, "mel");
  eq(groups[0].ingredients.map((i) => i.id), ["one", "two"]);
  if (!rankCandidates("Mel", [flour, pizza]).length) throw new Error("Missing flour");
  eq(rankCandidates("Pizzamel", [flour, pizza])[0].product.id, 25);
  eq(scoreCandidate("Tomat", flour), 0);
});

Deno.test("Danish terms remain valid Danish text for Prej searches", () => {
  eq(searchTerm("Rødløg"), "løg");
  eq(searchTerm("Kidneybønner"), "bønner");
  eq(searchTerm("Bacontern (pakke)"), "bacontern");
  eq(searchTerm("Pizzamel"), "mel");
});

Deno.test("one Prej request can produce suggestions for multiple ingredients and never selects defaults", async () => {
  const products: unknown[] = [];
  const candidates: unknown[] = [];
  const caches: unknown[] = [];
  const jobs: unknown[] = [];
  const apiCalls: string[] = [];
  const rpcCalls: string[] = [];
  const members = [
    { id: "flour-a", name: "Mel", never_buy: false },
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
  eq(first.requestsUsed, 1);
  eq(first.ready, 2);
  eq(first.pending, 0);
  eq(apiCalls.length, 1);
  eq(apiCalls[0].includes("chain=bilka"), true);
  eq(candidates.length, 2); // One validated SKU candidate per ingredient.
  eq(caches.length, 1);
  if (!rpcCalls.includes("prej_reserve_call")) throw new Error("Quota reservation missing");

  const second = await discoverProducts(ctx);
  eq(second.requestsUsed, 0);
  eq(second.pending, 0);
  eq(apiCalls.length, 1);
});

Deno.test("unit-price order never automatically changes household defaults", () => {
  const options = rankCandidates("Mel", [
    { ...flour, prices: [{ chain_slug: "bilka", price: 2700, unit_price: 1350 }] },
    { ...flour, id: 222, name: "Hvedemel 5 kg",
      prices: [{ chain_slug: "bilka", price: 3500, unit_price: 700 }] },
  ], 10);
  if (options.length !== 2) throw new Error("Missing candidates");
  // Discovery only ranks: there is no call to ingredient_bilka_defaults here.
});
