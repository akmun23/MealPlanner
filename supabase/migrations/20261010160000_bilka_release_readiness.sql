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
