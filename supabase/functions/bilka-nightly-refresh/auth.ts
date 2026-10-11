/**
 * Constant-time comparison of the presented cron bearer with a SHA-256
 * digest obtained through a service-role-only, Vault-backed PostgreSQL RPC.
 * Neither source code nor Edge Function environment variables contain the
 * plaintext cron secret.
 */
export async function matchesVaultDigest(
  provided: string,
  storedHexDigest: string | null,
): Promise<boolean> {
  if (provided.length < 64 || provided.length > 512 ||
      typeof storedHexDigest !== "string" ||
      !/^[0-9a-f]{64}$/.test(storedHexDigest)) return false;
  const actual = new Uint8Array(
    await crypto.subtle.digest("SHA-256", new TextEncoder().encode(provided)),
  );
  let difference = 0;
  for (let i = 0; i < actual.length; i++) {
    difference |= actual[i] ^ Number.parseInt(storedHexDigest.slice(i * 2, i * 2 + 2), 16);
  }
  return difference === 0;
}
