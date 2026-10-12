import { performNightly } from "./nightly.ts";
import { matchesVaultDigest } from "./auth.ts";

const URL = Deno.env.get("SUPABASE_URL") ?? "";
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

async function admin(path: string, method: "GET" | "POST" | "PATCH", body?: unknown, prefer?: string): Promise<unknown> {
  const res = await fetch(URL + path, {
    method,
    headers: {
      apikey: SERVICE_KEY,
      Authorization: "Bearer " + SERVICE_KEY,
      "Content-Type": "application/json",
      Prefer: prefer ?? "return=representation,resolution=merge-duplicates",
    },
    body: body == null ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(12000),
  });
  if (!res.ok) throw new Error("database_unavailable");
  const text = await res.text();
  return text ? JSON.parse(text) : null;
}

async function rpc(name: string, args: Record<string, unknown>): Promise<unknown> {
  if (!["prej_read_key", "prej_reserve_call", "prej_complete_call",
        "bilka_update_prices_only", "bilka_nightly_token_digest"].includes(name)) {
    throw new Error("not_allowed");
  }
  return await admin("/rest/v1/rpc/" + name, "POST", args);
}

function json(payload: unknown, status = 200): Response {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
  });
}

Deno.serve(async (request) => {
  if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  if (!URL || !SERVICE_KEY) return json({ error: "not_configured" }, 503);
  const presented = request.headers.get("x-mealplanner-cron-token") ?? "";
  // Reject malformed callers before any privileged database read.
  if (presented.length < 64 || presented.length > 512)
    return json({ error: "unauthorized" }, 401);
  let expectedDigest: unknown;
  try {
    expectedDigest = await rpc("bilka_nightly_token_digest", {});
  } catch {
    return json({ error: "not_configured" }, 503);
  }
  if (typeof expectedDigest !== "string")
    return json({ error: "not_configured" }, 503);
  if (!await matchesVaultDigest(presented, expectedDigest))
    return json({ error: "unauthorized" }, 401);

  try {
    return json(await performNightly({
      admin, rpc, providerFetch: fetch, now: new Date(),
    }));
  } catch {
    return json({ error: "nightly_refresh_failed" }, 503);
  }
});
