/** Pure key-registration orchestration. Never expose or log the key itself. */
export type PrejKeyRegistration = {
  connected: true;
  keyLastFour: string;
  providerRemaining: number | null;
  verificationPending: boolean;
};

export async function registerPrejKey(
  apiKey: string,
  check: (key: string) => Promise<number | null>,
  storeVerified: (key: string) => Promise<void>,
  stageUnverified: (key: string) => Promise<void>,
  isLocalBudgetError: (error: unknown) => boolean,
): Promise<PrejKeyRegistration> {
  let remaining: number | null;
  try {
    remaining = await check(apiKey);
  } catch (error) {
    if (!isLocalBudgetError(error)) throw error;
    // Stage only if the LOCAL budget prevented verification. A provider 401
    // or a provider 429 is not a reason to silently accept the key.
    await stageUnverified(apiKey);
    return {
      connected: true,
      keyLastFour: apiKey.slice(-4),
      providerRemaining: null,
      verificationPending: true,
    };
  }
  await storeVerified(apiKey);
  return {
    connected: true,
    keyLastFour: apiKey.slice(-4),
    providerRemaining: remaining,
    verificationPending: false,
  };
}
