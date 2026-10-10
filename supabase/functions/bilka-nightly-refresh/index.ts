import { performNightly } from "./nightly.ts";

const URL = Deno.env.get("SUPABASE_URL") ?? "";
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const CRON_TOKEN = Deno.env.get("BILKA_NIGHTLY_CRON_TOKEN") ?? "";

async function matchesSecret(given: string, expected: string): Promise<boolean> {
  // Compare only digests to avoid revealing the first differing character.
  if (expected.length < 32 || given.length < 32 || given.length > 512) return false;
  const encoder = new TextEncoder();
  const [a, b] = await Promise.all(
    [given, expected].map((s) => crypto.subtle.digest("SHA-256", encoder.encode(s)))
  );
  const left = new Uint8Array(a), right = new Uint8Array(b);
  let difference = 0;
  for (let i = 0; i < left.length; i++) difference |= left[i] ^ right[i];
  return difference === 0;
}

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
        "bilka_update_prices_only"].includes(name)) {
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
  if (!URL || !SERVICE_KEY || !CRON_TOKEN) return json({ error: "not_configured" }, 503);
  const allowed = await matchesSecret(
    request.headers.get("x-mealplanner-cron-token") ?? "", CRON_TOKEN,
  );
  if (!allowed) return json({ error: "unauthorized" }, 401);

  try {
    return json(await performNightly({
      admin, rpc, providerFetch: fetch, now: new Date(),
    }));
  } catch {
    return json({ error: "nightly_refresh_failed" }, 503);
  }
});
