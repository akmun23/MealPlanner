import { matchesVaultDigest } from "./auth.ts";

function ok(value: boolean, message: string) {
  if (!value) throw new Error(message);
}
async function sha256(value: string): Promise<string> {
  const d = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)));
  return Array.from(d, (v) => v.toString(16).padStart(2, "0")).join("");
}

Deno.test("only the exact Vault bearer hash is accepted", async () => {
  const correct = "a".repeat(95) + "x";
  const digest = await sha256(correct);
  ok(await matchesVaultDigest(correct, digest), "Valid cron token rejected");
  ok(!await matchesVaultDigest("a".repeat(95) + "y", digest), "Incorrect token accepted");
  ok(!await matchesVaultDigest(correct + "extra", digest), "Extended token accepted");
});

Deno.test("missing or malformed cron tokens never authorize calls", async () => {
  const valid = "b".repeat(96);
  const digest = await sha256(valid);
  ok(!await matchesVaultDigest("", digest), "Blank token accepted");
  ok(!await matchesVaultDigest("short", digest), "Short token accepted");
  ok(!await matchesVaultDigest("x".repeat(513), digest), "Oversized token accepted");
  ok(!await matchesVaultDigest(valid, null), "Missing digest accepted");
  ok(!await matchesVaultDigest(valid, "unknown"), "Invalid digest accepted");
  ok(!await matchesVaultDigest(valid, digest.toUpperCase()), "Invalid digest format accepted");
});
