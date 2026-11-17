-- Phase 5 nightly pricing: only explicit per-user opt-in may spend a personal Prej quota.
-- The cron endpoint stays inert until the separate token is provisioned in Vault + Edge secrets.
create table if not exists public.prej_nightly_preferences (
  household_id uuid not null,
  user_id uuid not null references auth.users(id) on delete cascade,
  enabled boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (household_id, user_id),
  foreign key (household_id, user_id)
    references public.household_members(household_id, user_id) on delete cascade
);

create table if not exists public.bilka_nightly_runs (
  household_id uuid not null references public.households(id) on delete cascade,
  local_date date not null,
  status text not null check (status in ('RUNNING','COMPLETE','PARTIAL','FAILED','NO_PRODUCTS')),
  attempted_by uuid references auth.users(id) on delete set null,
  products_updated integer not null default 0 check (products_updated >= 0),
  request_count integer not null default 0 check (request_count >= 0),
  error_code text,
  started_at timestamptz not null default now(),
  finished_at timestamptz,
  primary key (household_id, local_date)
);

alter table public.prej_nightly_preferences enable row level security;
alter table public.bilka_nightly_runs enable row level security;
revoke all on public.prej_nightly_preferences from public, anon, authenticated;
revoke all on public.bilka_nightly_runs from public, anon, authenticated;

comment on table public.prej_nightly_preferences is
  'Per-user opt-in for Prej calls in this household; service-only access.';
comment on table public.bilka_nightly_runs is
  'Once-per-local-day claim, so no second family member or cron retry is charged twice.';

create or replace function public.bilka_update_prices_only(p_updates jsonb)
returns integer
language plpgsql
security definer
set search_path = ''
as $price_only$
declare v_count integer;
begin
  if jsonb_typeof(p_updates) is distinct from 'array'
     or jsonb_array_length(p_updates) > 500 then
    raise exception 'Expected a JSON array with no more than 500 prices';
  end if;

  with incoming as (
    select distinct on (id)
      id, price_ore, unit_price_ore, unit_price_basis, is_offer, price_updated_at
    from jsonb_to_recordset(p_updates) as x(
      id text, price_ore bigint, unit_price_ore numeric,
      unit_price_basis text, is_offer boolean, price_updated_at timestamptz
    )
    where id ~ '^[0-9]+$' and price_ore >= 0
    order by id
  ), applied as (
    update public.bilka_products p
    set price_ore = i.price_ore,
        unit_price_ore = i.unit_price_ore,
        unit_price_basis = i.unit_price_basis,
        is_offer = i.is_offer,
        price_updated_at = coalesce(i.price_updated_at, now()),
        updated_at = now()
    from incoming i
    where p.id = i.id and p.chain_slug = 'bilka'
    returning p.id
  )
  select count(*) into v_count from applied;
  return v_count;
end;
$price_only$;

revoke all on function public.bilka_update_prices_only(jsonb) from public, anon, authenticated;
grant execute on function public.bilka_update_prices_only(jsonb) to service_role;

comment on function public.bilka_update_prices_only(jsonb) is
  'Service-role only: update price fields on existing Bilka products; never mutate chosen SKU, brand, package or photo.';

create extension if not exists pg_cron with schema pg_catalog;
create extension if not exists pg_net with schema extensions;

-- Replace <project-ref> with your Supabase project reference before applying.
-- This hourly job only calls the Edge Function during 05:00-07:59 Copenhagen
-- time and after a cron token is installed in Vault. It is otherwise inert.
select cron.schedule(
  'mealplanner-bilka-prices',
  '0 * * * *',
  $nightly_job$
    select net.http_post(
      url := 'https://<project-ref>.supabase.co/functions/v1/bilka-nightly-refresh',
      headers := jsonb_build_object(
        'Content-Type','application/json',
        'x-mealplanner-cron-token',
          (select decrypted_secret from vault.decrypted_secrets
           where name = 'bilka_nightly_cron_token' limit 1)
      ),
      body := '{}'::jsonb,
      timeout_milliseconds := 20000
    )
    where extract(hour from timezone('Europe/Copenhagen', now())) in (5, 6, 7)
      and exists (
        select 1 from vault.decrypted_secrets
        where name = 'bilka_nightly_cron_token'
        and length(decrypted_secret) >= 32
      );
  $nightly_job$
);
