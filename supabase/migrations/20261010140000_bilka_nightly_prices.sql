-- Bilka phase 5: opt-in nightly price refreshes, scoped to one user's own Prej key.
-- Defaults OFF. Cron does not call Prej until a member explicitly opts in,
-- a key has been verified, and the independent cron token has been configured.

create table public.prej_nightly_preferences (
    household_id uuid not null,
    user_id uuid not null references auth.users(id) on delete cascade,
    enabled boolean not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (household_id, user_id),
    foreign key (household_id, user_id)
        references public.household_members(household_id, user_id)
        on delete cascade
);

create table public.bilka_nightly_runs (
    household_id uuid not null references public.households(id) on delete cascade,
    local_date date not null,
    status text not null check (status in ('RUNNING', 'COMPLETE', 'PARTIAL', 'FAILED', 'NO_PRODUCTS')),
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
    'User-owned explicit consent for nightly Prej calls within this household. Service-only.';
comment on table public.bilka_nightly_runs is
    'Once-per-local-day claim prevents duplicated cron calls or charging two members for the same household refresh.';

-- Use the documented pg_cron + pg_net scheduling mechanism. It is intentionally
-- disabled until an operator provisions the *same* random secret in Supabase
-- Edge Function secrets and Vault (name bilka_nightly_cron_token).
create extension if not exists pg_cron with schema pg_catalog;
create extension if not exists pg_net with schema extensions;

select cron.schedule(
    'mealplanner-bilka-prices',
    '0 * * * *',
    $job$
    select net.http_post(
        url := 'https://tylwghmkkooflyaptwgf.supabase.co/functions/v1/bilka-nightly-refresh',
        headers := jsonb_build_object(
            'Content-Type', 'application/json',
            'x-mealplanner-cron-token',
                (select decrypted_secret from vault.decrypted_secrets
                  where name = 'bilka_nightly_cron_token' limit 1)
        ),
        body := '{}'::jsonb,
        timeout_milliseconds := 20000
    )
    where extract(hour from timezone('Europe/Copenhagen', now())) = 5
      and exists (
          select 1 from vault.decrypted_secrets
           where name = 'bilka_nightly_cron_token'
             and length(decrypted_secret) >= 32
      );
    $job$
);
