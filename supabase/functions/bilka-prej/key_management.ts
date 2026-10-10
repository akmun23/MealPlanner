/**
 * Replace a Prej credential without conflating saving the secret with spending
 * provider API quota. Staged credentials MUST NOT be used for searches until
 * explicitly verified via Prej at a later time.
 */
export type ReplacementOutcome = {
  verificationPending: boolean;
  providerRemaining: number | null;
};

export type KeyReplacementActions = {
  verify: () => Promise<number | null>;
  storeVerified: () => Promise<void>;
  stageUnverified: () => Promise<void>;
  isLocalQuotaExhausted: (error: unknown) => boolean;
};

export async function replacePrejCredential(
  actions: KeyReplacementActions,
): Promise<ReplacementOutcome> {
  let remaining: number | null;
  try {
    remaining = await actions.verify();
  } catch (error) {
    if (!actions.isLocalQuotaExhausted(error)) throw error;
    // No Prej request, no daily-usage reset, no verified credential.
    await actions.stageUnverified();
    return { verificationPending: true, providerRemaining: null };
  }

  await actions.storeVerified();
  return { verificationPending: false, providerRemaining: remaining };
}
