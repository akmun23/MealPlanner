-- Safe post-deployment test: no credentials, bearer tokens, or API calls are returned.
BEGIN;
DO $contract$
BEGIN
  IF (SELECT count(*) FROM vault.secrets
      WHERE name='bilka_nightly_cron_token') <> 1 THEN
    RAISE EXCEPTION 'Expected exactly one Vault cron token';
  END IF;
  IF EXISTS (
    SELECT 1 FROM vault.decrypted_secrets
    WHERE name='bilka_nightly_cron_token'
      AND length(decrypted_secret) < 64
  ) THEN
    RAISE EXCEPTION 'Cron bearer is too short';
  END IF;
  IF has_function_privilege('anon','public.bilka_nightly_token_digest()','EXECUTE')
    OR has_function_privilege('authenticated','public.bilka_nightly_token_digest()','EXECUTE')
    OR NOT has_function_privilege('service_role','public.bilka_nightly_token_digest()','EXECUTE')
  THEN
    RAISE EXCEPTION 'Nightly digest RPC grants are incorrect';
  END IF;
  IF public.bilka_nightly_token_digest() IS NULL OR
     length(public.bilka_nightly_token_digest()) <> 64 THEN
    RAISE EXCEPTION 'Vault digest is unavailable';
  END IF;
END;
$contract$;
ROLLBACK;
