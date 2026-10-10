-- Release readiness: don't promise nightly updates until the scheduled
-- job is active AND a credential has been safely provisioned in Vault.
-- The Edge secret must also be configured by the operator before activating the job.
create or replace function public.bilka_nightly_scheduler_ready()
returns boolean
language sql stable security definer
set search_path = ''
as $ready$
  select exists(
    select 1 from cron.job
    where jobname = 'mealplanner-bilka-prices' and active
  )
  and exists(
    select 1 from vault.decrypted_secrets
    where name = 'bilka_nightly_cron_token'
      and length(decrypted_secret) >= 32
  );
$ready$;

revoke all on function public.bilka_nightly_scheduler_ready()
  from public, anon, authenticated;
grant execute on function public.bilka_nightly_scheduler_ready()
  to service_role;

comment on function public.bilka_nightly_scheduler_ready() is
  'Server-side readiness check only, never exposes Vault secret or cron SQL.';

-- Secondary indexes for frequent household ingredient/weekly shopping joins.
-- Primary keys start with household_id; these indexes cover foreign-key
-- operations that start with ingredient_id / weekly_plan_id instead.
create index if not exists idx_bilka_candidates_ingredient_household
  on public.ingredient_bilka_candidates(ingredient_id, household_id);
create index if not exists idx_bilka_defaults_ingredient_household
  on public.ingredient_bilka_defaults(ingredient_id, household_id);
create index if not exists idx_bilka_jobs_ingredient_household
  on public.bilka_discovery_jobs(ingredient_id, household_id);
create index if not exists idx_bilka_shopping_ingredient_household
  on public.shopping_product_overrides(ingredient_id, household_id);
create index if not exists idx_bilka_shopping_week_household
  on public.shopping_product_overrides(weekly_plan_id, household_id);
create index if not exists idx_bilka_nightly_attempted_by
  on public.bilka_nightly_runs(attempted_by);
create index if not exists idx_bilka_nightly_prefs_user
  on public.prej_nightly_preferences(user_id);
