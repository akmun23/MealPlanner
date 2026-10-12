-- Bootstrap nightly cron authentication without requiring an operator to copy
-- a secret into Edge Function environment variables. Postgres generates the
-- bearer once, encrypts it in Vault, and never puts it in Git/SQL logs.
-- The existing pg_cron command reads this secret only at execution time.
DO $bootstrap$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM vault.secrets WHERE name = 'bilka_nightly_cron_token'
  ) THEN
    PERFORM vault.create_secret(
      pg_catalog.encode(extensions.gen_random_bytes(48), 'hex'),
      'bilka_nightly_cron_token',
      'Private bearer for MealPlanner nightly price cron; never expose to clients'
    );
  END IF;
END;
$bootstrap$;

-- Authenticated only as Supabase service_role. The Edge worker receives
-- this non-reusable digest rather than any decrypted bearer value.
CREATE OR REPLACE FUNCTION public.bilka_nightly_token_digest()
RETURNS text
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = ''
AS $nightly_digest$
  SELECT pg_catalog.encode(
    pg_catalog.sha256(pg_catalog.convert_to(decrypted_secret, 'UTF8')),
    'hex'
  )
  FROM vault.decrypted_secrets
  WHERE name = 'bilka_nightly_cron_token'
    AND pg_catalog.length(decrypted_secret) >= 64
  LIMIT 1;
$nightly_digest$;

REVOKE ALL ON FUNCTION public.bilka_nightly_token_digest()
  FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.bilka_nightly_token_digest()
  TO service_role;

COMMENT ON FUNCTION public.bilka_nightly_token_digest() IS
  'Service-only SHA-256 digest of private cron bearer. Never returns the Vault token.';

-- Activation itself is an operational action performed after the new Edge
-- worker has passed a live unauthorized/authorized smoke test. No user ID,
-- private key, or personal quota consent is embedded in this migration.
