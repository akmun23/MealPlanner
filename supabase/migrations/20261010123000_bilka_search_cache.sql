-- Phase 3: shared Bilka search-result cache for grouping and deduplication.
-- Product matches remain household/ingredient-specific and require manual selection.
create table public.bilka_search_cache (
    query_key text primary key
        check (length(query_key) between 1 and 200),
    product_ids text[] not null default '{}',
    result_total integer not null default 0 check (result_total >= 0),
    page integer not null default 1 check (page >= 1),
    truncated boolean not null default false,
    fetched_at timestamptz not null default now(),
    expires_at timestamptz not null default (now() + interval '7 days')
);

comment on table public.bilka_search_cache is
    'Server-owned cache of Prej Bilka-only product searches. One query can supply suggestions for many ingredients. Never store API credentials here.';

create index idx_bilka_search_cache_expires
    on public.bilka_search_cache(expires_at);

alter table public.bilka_search_cache enable row level security;

revoke all on public.bilka_search_cache from public, anon, authenticated;
-- Intentionally no policies: only server-side service_role can read/write.

-- Keep discovery jobs prioritized by insertion and retry time.
create index if not exists idx_bilka_discovery_jobs_household_status
    on public.bilka_discovery_jobs(household_id, status, created_at);

comment on column public.ingredient_bilka_candidates.match_score is
    'Conservative, unverified product-name matching score. Users must explicitly choose a default SKU.';

-- A capped 100-result page is not proof that an ingredient has no matching
-- products. Keep an explicit state for cases requiring a narrower manual search.
alter table public.bilka_discovery_jobs
    drop constraint if exists bilka_discovery_jobs_status_check;
alter table public.bilka_discovery_jobs
    add constraint bilka_discovery_jobs_status_check
    check (status in ('PENDING', 'RUNNING', 'COMPLETE', 'NO_MATCH', 'FAILED', 'INCOMPLETE'));
