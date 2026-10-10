import { replacePrejCredential } from "./key_management.ts";

function equal(actual: unknown, expected: unknown) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error("Expected " + JSON.stringify(expected) +
      " but got " + JSON.stringify(actual));
  }
}

Deno.test("quota exhaustion stages replacement without extra provider calls or verified status", async () => {
  const calls: string[] = [];
  const quotaExceeded = new Error("daily_budget_exhausted");
  const result = await replacePrejCredential({
    verify: () => {
      calls.push("check_budget");
      throw quotaExceeded;
    },
    storeVerified: async () => { calls.push("store_verified"); },
    stageUnverified: async () => { calls.push("stage_unverified"); },
    isLocalQuotaExhausted: (err) => err === quotaExceeded,
  });
  equal(result, { verificationPending: true, providerRemaining: null });
  equal(calls, ["check_budget", "stage_unverified"]);
});

Deno.test("successful provider verification saves a verified replacement", async () => {
  const calls: string[] = [];
  const result = await replacePrejCredential({
    verify: async () => { calls.push("provider_check"); return 12; },
    storeVerified: async () => { calls.push("store_verified"); },
    stageUnverified: async () => { calls.push("stage_unverified"); },
    isLocalQuotaExhausted: () => false,
  });
  equal(result, { verificationPending: false, providerRemaining: 12 });
  equal(calls, ["provider_check", "store_verified"]);
});

Deno.test("invalid provider keys are never staged or silently accepted", async () => {
  const calls: string[] = [];
  const invalid = new Error("invalid_key");
  let thrown: unknown;
  try {
    await replacePrejCredential({
      verify: async () => { throw invalid; },
      storeVerified: async () => { calls.push("store_verified"); },
      stageUnverified: async () => { calls.push("stage_unverified"); },
      isLocalQuotaExhausted: () => false,
    });
  } catch (error) { thrown = error; }
  equal(thrown, invalid);
  equal(calls, []);
});

Deno.test("failed persistence does not accidentally report a verified credential", async () => {
  let reported = false;
  try {
    await replacePrejCredential({
      verify: async () => 12,
      storeVerified: async () => { throw new Error("vault_unavailable"); },
      stageUnverified: async () => { throw new Error("unexpected_stage"); },
      isLocalQuotaExhausted: () => false,
    });
    reported = true;
  } catch {
    // Vault persistence failed; caller must report a failure.
  }
  equal(reported, false);
});
