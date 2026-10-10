import { copenhagenLocal, pickNightlyOwners, performNightly } from "./nightly.ts";

function equal(got: unknown, expected: unknown) {
  if (JSON.stringify(got) !== JSON.stringify(expected))
    throw new Error("Expected " + JSON.stringify(expected) + " got " + JSON.stringify(got));
}
const member = { household_id: "h", user_id: "owner" };
const pref = { ...member, enabled: true, created_at: "2026-10-01T10:00:00Z" };
const creds = [{ user_id: "owner", enabled: true, verified_at: "2026-10-09T12:00:00Z" }];
const chosen = [{ household_id: "h", product_id: "101" }];

Deno.test("Copenhagen 05:00 changes UTC offset across daylight saving", () => {
  equal(copenhagenLocal(new Date("2026-03-29T03:15:00Z")), {
    localDate: "2026-03-29", hour: 5,
  });
  equal(copenhagenLocal(new Date("2026-10-25T04:15:00Z")), {
    localDate: "2026-10-25", hour: 5,
  });
});

Deno.test("consent, key verification, membership, and SKU needed", () => {
  equal(pickNightlyOwners([pref], creds, [member], chosen, [], "2026-10-10").length, 1);
  equal(pickNightlyOwners([{ ...pref, enabled: false }], creds, [member], chosen, [], "2026-10-10"), []);
  equal(pickNightlyOwners([pref], [{ ...creds[0], verified_at: null }], [member], chosen, [], "2026-10-10"), []);
  equal(pickNightlyOwners([pref], creds, [], chosen, [], "2026-10-10"), []);
  equal(pickNightlyOwners([pref], creds, [member], [], [], "2026-10-10"), []);
  equal(pickNightlyOwners([pref], creds, [member], chosen,
    [{ household_id: "h", local_date: "2026-10-10" }], "2026-10-10"), []);
});

Deno.test("two opted-in people never rotate or pool their Prej keys", () => {
  const other = { household_id: "h", user_id: "partner" };
  const choices = pickNightlyOwners(
    [pref, { ...other, enabled: true, created_at: "2026-10-02T10:00:00Z" }],
    [...creds, { user_id: "partner", enabled: true, verified_at: "2026-10-09T12:00:00Z" }],
    [member, other], chosen, [], "2026-10-10",
  );
  equal(choices.length, 1);
  equal(choices[0].userId, "owner");
});

Deno.test("worker only patches price fields, leaves exact selected brand/size untouched", async () => {
  let used = 0;
  let selectedWrites = 0;
  const priceUpdates: Array<Record<string, unknown>> = [];
  const history: Array<{ household_id: string; local_date: string }> = [];
  const deps = {
    now: new Date("2026-10-10T03:15:00Z"),
    admin: async (path: string, method: "GET" | "POST" | "PATCH",
      body?: unknown): Promise<unknown> => {
      if (path.includes("/prej_nightly_preferences?")) return [pref];
      if (path.includes("/prej_user_credentials?")) return creds;
      if (path.includes("/household_members?")) return [member];
      if (path.includes("/ingredient_bilka_defaults?")) {
        if (method !== "GET") selectedWrites++;
        return chosen;
      }
      if (path.includes("/bilka_nightly_runs?")) {
        if (method === "GET") return history;
        if (method === "POST") {
          const item = (body as Array<{ household_id: string; local_date: string }>)[0];
          if (history.some((h) => h.household_id === item.household_id &&
            h.local_date === item.local_date)) return [];
          history.push(item);
          return [item];
        }
        return body;
      }
      throw new Error("Unexpected database path " + path);
    },
    rpc: async (name: string, args: Record<string, unknown>): Promise<unknown> => {
      if (name === "prej_read_key") return "secret-not-logged";
      if (name === "prej_reserve_call") return true;
      if (name === "bilka_update_prices_only") {
        priceUpdates.push(...args.p_updates as Array<Record<string, unknown>>);
        return (args.p_updates as unknown[]).length;
      }
      return null;
    },
    providerFetch: (async () => {
      used++;
      return Response.json({
        products: [
          {
            id: 101, name: "Changed provider label", brand: "New brand",
            prices: [{ chain_slug: "bilka", price: 2495, unit_price: 1247,
              last_seen_date: "2026-10-10", is_offer: false }],
          },
          {
            id: 1010, name: "Different product",
            prices: [{ chain_slug: "bilka", price: 999 }],
          },
          {
            id: 101, name: "Wrong shop",
            prices: [{ chain_slug: "bilkatogo", price: 999 }],
          },
        ],
      });
    }) as typeof fetch,
  };
  const first = await performNightly(deps);
  equal(first, { processed: 1, updated: 1, failures: 0 });
  equal(used, 1);
  equal(selectedWrites, 0);
  equal(priceUpdates.length, 1);
  equal(priceUpdates[0].id, "101");
  equal(priceUpdates[0].price_ore, 2495);
  if ("brand" in priceUpdates[0] || "package_quantity" in priceUpdates[0] ||
      "name" in priceUpdates[0] || "image_url" in priceUpdates[0])
    throw new Error("Worker must not change fixed SKU metadata");
  const again = await performNightly(deps);
  equal(again, { processed: 0, updated: 0, failures: 0 });
  equal(used, 1);
});

Deno.test("outside Copenhagen 05–07 worker makes no API calls", async () => {
  const result = await performNightly({
    now: new Date("2026-10-10T12:00:00Z"),
    admin: async () => { throw new Error("Should never query DB"); },
    rpc: async () => { throw new Error("Should never query Vault"); },
    providerFetch: (async () => { throw new Error("Must never call Prej"); }) as typeof fetch,
  });
  equal(result, { processed: 0, updated: 0, skipped: "outside_local_window" });
});
