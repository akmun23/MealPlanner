-- Run after phase-7 migration: verify scheduler readiness reflects disabled cron.
-- This test does not change existing scheduling, keys or app data.
begin;
do $verify$
declare ready boolean;
begin
  if has_function_privilege('anon','public.bilka_nightly_scheduler_ready()','EXECUTE')
     or has_function_privilege('authenticated','public.bilka_nightly_scheduler_ready()','EXECUTE')
     or not has_function_privilege('service_role','public.bilka_nightly_scheduler_ready()','EXECUTE')
  then
    raise exception 'Nightly readiness endpoint exposed to public';
  end if;

  select public.bilka_nightly_scheduler_ready() into ready;
  if ready and not exists (
    select 1 from cron.job where jobname='mealplanner-bilka-prices' and active
  ) then
    raise exception 'Cron is disabled but nightly readiness reported true';
  end if;

  if ready and not exists (
    select 1 from vault.decrypted_secrets
    where name='bilka_nightly_cron_token' and length(decrypted_secret)>=32
  ) then
    raise exception 'Cron Vault secret not configured but readiness reported true';
  end if;
  if to_regclass('public.idx_bilka_candidates_ingredient_household') is null
     or to_regclass('public.idx_bilka_defaults_ingredient_household') is null
     or to_regclass('public.idx_bilka_jobs_ingredient_household') is null
     or to_regclass('public.idx_bilka_shopping_ingredient_household') is null
     or to_regclass('public.idx_bilka_shopping_week_household') is null
     or to_regclass('public.idx_bilka_nightly_attempted_by') is null
     or to_regclass('public.idx_bilka_nightly_prefs_user') is null
  then
    raise exception 'A required Bilka foreign-key join index is missing';
  end if;
  raise notice 'Readiness permissions, scheduling and all join indexes verified';
end
$verify$;
rollback;
