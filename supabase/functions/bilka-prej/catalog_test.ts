import { catalog, eligibleChoice, saveHouseholdDefault } from "./catalog_server.ts";

const id = "0a132cc4-7633-40d3-b317-47b2a99192fe";
function eq(a: unknown, b: unknown) {
  if (JSON.stringify(a) !== JSON.stringify(b))
    throw new Error("Expected " + JSON.stringify(b) + "; got " + JSON.stringify(a));
}

Deno.test("only suggested product IDs are eligible for explicit save", () => {
  const allowed = [{ ingredient_id: id, product_id: "42" }];
  eq(eligibleChoice(id, "42", allowed), true);
  eq(eligibleChoice(id, "43", allowed), false);
  eq(eligibleChoice("other-household-ingredient", "42", allowed), false);
});

Deno.test("catalog joins brand/price metadata without writing defaults", async () => {
  const requests: string[] = [];
  const house = "ce85a721-78a3-40c5-a18d-13b1011ba54b";
  const services = {
    householdId: house,
    admin: async (path: string, method: "GET" | "POST"): Promise<unknown> => {
      requests.push(method + " " + path);
      if (method !== "GET") throw new Error("catalog cannot write");
      if (path.includes("/ingredient_bilka_candidates")) return [{
        ingredient_id: id, product_id: "42", display_rank: 0,
      }];
      if (path.includes("/ingredient_bilka_defaults")) return [{
        ingredient_id: id, product_id: "42",
      }];
      if (path.includes("/bilka_products")) return [{
        id: "42", name: "Hvedemel", brand: "Budget",
        package_quantity: 2, package_unit: "kg",
        price_ore: 1900, unit_price_ore: 950, image_url: null,
      }];
      throw new Error("Unrecognized catalog GET");
    },
  };
  const result = await catalog(services);
  eq(result.candidates.length, 1);
  eq(result.defaults[0].product_id, "42");
  eq(result.products[0].brand, "Budget");
  eq(result.products[0].package_quantity, 2);
  eq(requests.every((r) => r.startsWith("GET ")), true);
  eq(requests.some((r) => r.includes("household_id=eq." + house)), true);
});

Deno.test("explicit save only updates one ingredient default, never its candidates", async () => {
  const writes: Array<Record<string, unknown>> = [];
  const services = {
    householdId: "ce85a721-78a3-40c5-a18d-13b1011ba54b",
    admin: async (path: string, method: "GET" | "POST", body?: unknown): Promise<unknown> => {
      if (method === "GET") return [{
        ingredient_id: id, product_id: "42",
      }];
      if (!path.startsWith("/rest/v1/ingredient_bilka_defaults"))
        throw new Error("Must not write search candidates");
      writes.push(...(body as Record<string, unknown>[]));
      return body;
    },
  };
  eq(await saveHouseholdDefault(services, id, "42"), {
    saved: true, ingredientId: id, productId: "42",
  });
  eq(writes, [{
    household_id: services.householdId, ingredient_id: id, product_id: "42",
  }]);
  const rejected = await saveHouseholdDefault(services, id, "bad");
  eq(rejected.error, "invalid_product_choice");
  eq(writes.length, 1);
});

Deno.test("ingredient-scoped catalog avoids loading unrelated products", async () => {
  const paths: string[] = [];
  const house = "ce85a721-78a3-40c5-a18d-13b1011ba54b";
  const service = {
    householdId: house,
    admin: async (path: string): Promise<unknown> => {
      paths.push(path);
      if (path.includes("/ingredient_bilka_candidates?")) return [{
        ingredient_id: id, product_id: "42", display_rank: 0,
      }];
      if (path.includes("/ingredient_bilka_defaults?")) return [];
      if (path.includes("/bilka_products?")) return [{
        id: "42", name: "Hvedemel", brand: "Mærke",
      }];
      throw new Error("Unexpected path " + path);
    },
  };
  const result = await catalog(service, [id]);
  eq(result.products.length, 1);
  eq(paths.filter((p) => p.includes("ingredient_id=in.(" + id + ")")).length, 2);

  paths.length = 0;
  const empty = await catalog(service, []);
  eq(empty.products.length, 0);
  eq(paths.length, 0);

  let rejected = false;
  try {
    await catalog(service, ["not-an-ingredient-id"]);
  } catch {
    rejected = true;
  }
  eq(rejected, true);
  eq(paths.length, 0);
});

Deno.test("previously selected SKU stays available when suggestions are cleared", async () => {
  const house = "ce85a721-78a3-40c5-a18d-13b1011ba54b";
  const requests: string[] = [];
  const result = await catalog({
    householdId: house,
    admin: async (path: string, method: "GET" | "POST"): Promise<unknown> => {
      if (method !== "GET") throw new Error("Catalog is read-only");
      requests.push(path);
      if (path.includes("/ingredient_bilka_candidates?")) return [];
      if (path.includes("/ingredient_bilka_defaults?"))
        return [{ ingredient_id: id, product_id: "42" }];
      if (path.includes("/bilka_products?"))
        return [{ id: "42", name: "Fixed flour 1kg", price_ore: 1200 }];
      throw new Error("Unexpected path " + path);
    },
  }, [id]);
  eq(result.candidates, []);
  eq(result.defaults, [{ ingredient_id: id, product_id: "42" }]);
  eq(result.products.map((p) => p.id), ["42"]);
  eq(requests.some((path) => path.includes("id=in.(42)")), true);
});
