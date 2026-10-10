-- Bilka integration, phase 1: schema and access control only.
-- No product import, external requests, scheduled jobs or raw credentials are introduced here.
-- Keep the chosen product immutable across price refreshes: the worker only updates bilka_products.

create table public.bilka_products (
    id text primary key check (length(btrim(id)) > 0),
    chain_slug text not null default 'bilka'
        check (chain_slug = 'bilka'),
    name text not null check (length(btrim(name)) > 0),
    brand text,
    package_quantity numeric(14, 4)
        check (package_quantity is null or package_quantity > 0),
    package_unit text,
    -- In MealPlanner base units: g, ml, pieces, or packages.
    package_base_amount numeric(14, 4)
        check (package_base_amount is null or package_base_amount > 0),
    quantity_kind text
        check (quantity_kind in ('MASS', 'VOLUME', 'COUNT', 'PACKAGE')),
    image_url text,
    price_ore bigint check (price_ore is null or price_ore >= 0),
    unit_price_ore numeric(14, 4)
        check (unit_price_ore is null or unit_price_ore >= 0),
    unit_price_basis text
        check (unit_price_basis in ('KG', 'L', 'PIECE', 'PACK')),
    is_offer boolean,
    is_available boolean,
    price_updated_at timestamptz,
    first_seen_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    check (package_base_amount is null or quantity_kind is not null)
);

comment on table public.bilka_products is
    'Server-maintained Bilka-only product cache. Product ID identifies a precise brand and pack size.';
comment on column public.bilka_products.image_url is
    'Optional third-party image URL; availability and reuse rights must be verified before display.';

create table public.ingredient_bilka_candidates (
    household_id uuid not null,
    ingredient_id uuid not null,
    product_id text not null references public.bilka_products(id) on delete cascade,
    match_status text not null default 'SUGGESTED'
        check (match_status in ('SUGGESTED', 'REJECTED')),
    match_score numeric(6, 5)
        check (match_score is null or match_score between 0 and 1),
    display_rank integer not null default 0 check (display_rank >= 0),
    created_at timestamptz not null default now(),
    primary key (household_id, ingredient_id, product_id),
    foreign key (ingredient_id, household_id)
        references public.ingredients(id, household_id) on delete cascade
);

comment on table public.ingredient_bilka_candidates is
    'Worker-generated suggestions per ingredient; not the user-selected default.';

create index idx_ingredient_bilka_candidates_product
    on public.ingredient_bilka_candidates(product_id);

create table public.ingredient_bilka_defaults (
    household_id uuid not null,
    ingredient_id uuid not null,
    product_id text not null references public.bilka_products(id) on delete restrict,
    -- Optional correction when provider packaging data is missing/inaccurate.
    package_base_amount_override numeric(14, 4)
        check (package_base_amount_override is null or package_base_amount_override > 0),
    chosen_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (household_id, ingredient_id),
    foreign key (ingredient_id, household_id)
        references public.ingredients(id, household_id) on delete cascade
);

comment on table public.ingredient_bilka_defaults is
    'Exactly one fixed Bilka product per household ingredient; price refresh never rewrites it.';

create index idx_ingredient_bilka_defaults_product
    on public.ingredient_bilka_defaults(product_id);

create table public.shopping_product_overrides (
    household_id uuid not null,
    weekly_plan_id uuid not null,
    ingredient_id uuid not null,
    product_id text references public.bilka_products(id) on delete restrict,
    manual_product_name text,
    package_count integer check (package_count is null or package_count > 0),
    -- Actual amount purchased in the ingredient base unit, for stock updates.
    purchased_amount_base numeric(14, 4)
        check (purchased_amount_base is null or purchased_amount_base > 0),
    paid_total_ore bigint check (paid_total_ore is null or paid_total_ore >= 0),
    updated_at timestamptz not null default now(),
    primary key (weekly_plan_id, ingredient_id),
    foreign key (weekly_plan_id, household_id)
        references public.weekly_plans(id, household_id) on delete cascade,
    foreign key (ingredient_id, household_id)
        references public.ingredients(id, household_id) on delete cascade,
    check (
        (product_id is not null) <> (manual_product_name is not null)
    ),
    check (manual_product_name is null or length(btrim(manual_product_name)) > 0)
);

comment on table public.shopping_product_overrides is
    'Week-specific alternative purchase; does not modify ingredient_bilka_defaults.';

create index idx_shopping_product_overrides_household
    on public.shopping_product_overrides(household_id, weekly_plan_id);
create index idx_shopping_product_overrides_product
    on public.shopping_product_overrides(product_id);

create table public.bilka_discovery_jobs (
    id uuid primary key default gen_random_uuid(),
    household_id uuid not null,
    ingredient_id uuid not null,
    search_query text,
    status text not null default 'PENDING'
        check (status in ('PENDING', 'RUNNING', 'COMPLETE', 'NO_MATCH', 'FAILED')),
    attempts integer not null default 0 check (attempts >= 0),
    last_error text,
    next_attempt_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (household_id, ingredient_id),
    foreign key (ingredient_id, household_id)
        references public.ingredients(id, household_id) on delete cascade
);

comment on table public.bilka_discovery_jobs is
    'Deduplicated server-owned queue for ingredient search within the daily Prej budget.';

create index idx_bilka_discovery_jobs_pending
    on public.bilka_discovery_jobs(status, next_attempt_at, created_at)
    where status in ('PENDING', 'FAILED');

-- Personal Prej credentials are encrypted in Supabase Vault by a later Edge Function.
-- Only an opaque Vault secret reference and redacted metadata are stored here.
-- This table must not have any direct authenticated/anon grants or RLS policies.
create table public.prej_user_credentials (
    user_id uuid primary key references auth.users(id) on delete cascade,
    vault_secret_id uuid not null unique,
    key_last_four text not null check (length(key_last_four) = 4),
    enabled boolean not null default true,
    verified_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

comment on table public.prej_user_credentials is
    'Server-only metadata and Supabase Vault reference for each user-owned Prej key. Never contains plaintext keys.';

-- Quotas are PER USER/PREJ ACCOUNT, never pooled among household members.
-- A future server-only operation must reserve calls atomically before Prej requests.
create table public.prej_api_usage (
    user_id uuid not null references public.prej_user_credentials(user_id) on delete cascade,
    quota_date_utc date not null,
    primary key (user_id, quota_date_utc),
    calls_reserved integer not null default 0 check (calls_reserved >= 0),
    calls_completed integer not null default 0 check (calls_completed >= 0),
    last_remaining_reported integer check (
        last_remaining_reported is null or last_remaining_reported >= 0
    ),
    updated_at timestamptz not null default now(),
    check (calls_completed <= calls_reserved)
);

comment on table public.prej_api_usage is
    'Server-only per-user UTC daily quota accounting. Never pools credentials or quotas.';

-- Every table in the exposed public schema has RLS.
alter table public.bilka_products enable row level security;
alter table public.ingredient_bilka_candidates enable row level security;
alter table public.ingredient_bilka_defaults enable row level security;
alter table public.shopping_product_overrides enable row level security;
alter table public.bilka_discovery_jobs enable row level security;
alter table public.prej_user_credentials enable row level security;
alter table public.prej_api_usage enable row level security;

-- Revoke default public-schema grants before granting minimal permissions.
revoke all on public.bilka_products from anon, authenticated;
revoke all on public.ingredient_bilka_candidates from anon, authenticated;
revoke all on public.ingredient_bilka_defaults from anon, authenticated;
revoke all on public.shopping_product_overrides from anon, authenticated;
revoke all on public.bilka_discovery_jobs from anon, authenticated;
revoke all on public.prej_user_credentials from anon, authenticated;
revoke all on public.prej_api_usage from anon, authenticated;

-- Non-sensitive product catalog is read-only for authenticated app users.
grant select on public.bilka_products to authenticated;
create policy bilka_products_read_authenticated
on public.bilka_products for select to authenticated
using (true);

-- Household-specific candidates and queue status are client-read-only.
grant select on public.ingredient_bilka_candidates to authenticated;
create policy ingredient_bilka_candidates_read_household
on public.ingredient_bilka_candidates for select to authenticated
using (private.is_household_member(household_id));

grant select on public.bilka_discovery_jobs to authenticated;
create policy bilka_discovery_jobs_read_household
on public.bilka_discovery_jobs for select to authenticated
using (private.is_household_member(household_id));

-- Both household members can select a default and record one-off purchases.
grant select, insert, update, delete on public.ingredient_bilka_defaults to authenticated;
create policy ingredient_bilka_defaults_household_all
on public.ingredient_bilka_defaults for all to authenticated
using (private.is_household_member(household_id))
with check (private.is_household_member(household_id));

grant select, insert, update, delete on public.shopping_product_overrides to authenticated;
create policy shopping_product_overrides_household_all
on public.shopping_product_overrides for all to authenticated
using (private.is_household_member(household_id))
with check (private.is_household_member(household_id));

-- prej_user_credentials and prej_api_usage deliberately have no client policies or client grants.
-- They must be accessed only by authenticated/authorized server-side operations.
