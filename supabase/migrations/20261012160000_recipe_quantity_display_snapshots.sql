-- Add presentation-only snapshots to recipe ingredient requirements.
-- Physical calculations continue to use recipe_ingredients.amount_base.
-- Existing rows are deliberately NOT backfilled with guessed package sizes.
-- Run before installing clients which save display_amount/display_unit_code.
alter table public.recipe_ingredients
    add column if not exists display_amount double precision,
    add column if not exists display_unit_code text,
    add column if not exists base_per_display_unit double precision,
    add column if not exists conversion_approximate boolean not null default false;

-- All presentation fields are optional for legacy recipes. A complete new
-- snapshot must contain positive finite factors/amounts and a safe unit code.
-- Do not constrain amount_base to snapshot conversion here: older APKs may
-- still update physical amounts without touching the presentation columns.
do $body$
begin
    if not exists (
        select 1
        from pg_constraint
        where conrelid = 'public.recipe_ingredients'::regclass
          and conname = 'recipe_ingredient_display_snapshot_valid'
    ) then
        alter table public.recipe_ingredients
        add constraint recipe_ingredient_display_snapshot_valid
        check (
            (
                display_amount is null
                and display_unit_code is null
                and base_per_display_unit is null
            ) or (
                display_amount is not null
                and display_unit_code is not null
                and base_per_display_unit is not null
                and display_amount > 0 and display_amount <= 10000000000
                and base_per_display_unit > 0
                and base_per_display_unit <= 1000000000
                and display_unit_code ~ '^[A-Z][A-Z0-9_]{0,39}$'
            )
        );
    end if;
end
$body$;

comment on column public.recipe_ingredients.display_amount is
    'Frozen user-authored recipe amount; does not track later product pack sizes';
comment on column public.recipe_ingredients.display_unit_code is
    'Unit chosen by recipe author at save time, e.g. PACK or GRAM';
comment on column public.recipe_ingredients.base_per_display_unit is
    'Confirmed conversion factor at save time, NOT a live product default';
comment on column public.recipe_ingredients.conversion_approximate is
    'Whether the frozen recipe conversion factor was estimated, not exact';


-- Phase 2: ingredient-scoped household conversion profiles.
-- An old Android client never reads or writes this table, so changing a
-- favorite ingredient cannot accidentally erase a modern conversion profile.
create table if not exists public.ingredient_unit_conversions (
    household_id uuid not null,
    ingredient_id uuid not null,
    unit_code text not null check (unit_code ~ '^[A-Z][A-Z0-9_]{0,39}$'),
    base_amount_per_unit numeric not null
        check (base_amount_per_unit > 0 and base_amount_per_unit <= 1000000000),
    approximate boolean not null default false,
    source text not null default 'household-confirmed'
        check (length(source) between 1 and 120),
    revision bigint not null default 1 check (revision > 0),
    updated_at timestamptz not null default now(),
    primary key (household_id, ingredient_id, unit_code),
    foreign key (ingredient_id, household_id)
        references public.ingredients(id, household_id) on delete cascade
);
create index if not exists ingredient_unit_conversions_ingredient_idx
    on public.ingredient_unit_conversions(ingredient_id);
alter table public.ingredient_unit_conversions enable row level security;
revoke all on public.ingredient_unit_conversions from anon;
grant select, insert, update, delete on public.ingredient_unit_conversions to authenticated;
drop policy if exists ingredient_unit_conversion_select on public.ingredient_unit_conversions;
create policy ingredient_unit_conversion_select on public.ingredient_unit_conversions
    for select to authenticated
    using (private.is_household_member(household_id));
drop policy if exists ingredient_unit_conversion_insert on public.ingredient_unit_conversions;
create policy ingredient_unit_conversion_insert on public.ingredient_unit_conversions
    for insert to authenticated
    with check (private.is_household_member(household_id));
drop policy if exists ingredient_unit_conversion_update on public.ingredient_unit_conversions;
create policy ingredient_unit_conversion_update on public.ingredient_unit_conversions
    for update to authenticated
    using (private.is_household_member(household_id))
    with check (private.is_household_member(household_id));
drop policy if exists ingredient_unit_conversion_delete on public.ingredient_unit_conversions;
create policy ingredient_unit_conversion_delete on public.ingredient_unit_conversions
    for delete to authenticated
    using (private.is_household_member(household_id));

-- Preserve the optional display snapshot when an older installed APK saves
-- the same physical recipe quantity via DELETE + INSERT. This archive is
-- private (no direct API grants). A change to amount_base never restores
-- an obsolete presentation.
create table if not exists public.recipe_quantity_snapshot_restore (
    household_id uuid not null,
    recipe_id uuid not null,
    ingredient_id uuid not null,
    amount_base numeric not null,
    display_amount double precision not null,
    display_unit_code text not null,
    base_per_display_unit double precision not null,
    conversion_approximate boolean not null,
    captured_at timestamptz not null default now(),
    primary key (household_id, recipe_id, ingredient_id)
);
revoke all on public.recipe_quantity_snapshot_restore from public, anon, authenticated;
alter table public.recipe_quantity_snapshot_restore enable row level security;
create or replace function private.capture_recipe_quantity_snapshot()
returns trigger language plpgsql security definer set search_path = ''
as $body$
begin
    if old.display_amount is not null
       and old.display_unit_code is not null
       and old.base_per_display_unit is not null
       and old.base_per_display_unit > 0 then
        insert into public.recipe_quantity_snapshot_restore (
            household_id, recipe_id, ingredient_id, amount_base,
            display_amount, display_unit_code, base_per_display_unit,
            conversion_approximate, captured_at
        ) values (
            old.household_id, old.recipe_id, old.ingredient_id, old.amount_base,
            old.display_amount, old.display_unit_code, old.base_per_display_unit,
            old.conversion_approximate, now()
        ) on conflict (household_id, recipe_id, ingredient_id) do update set
            amount_base = excluded.amount_base,
            display_amount = excluded.display_amount,
            display_unit_code = excluded.display_unit_code,
            base_per_display_unit = excluded.base_per_display_unit,
            conversion_approximate = excluded.conversion_approximate,
            captured_at = now();
    end if;
    return old;
end;
$body$;
revoke all on function private.capture_recipe_quantity_snapshot() from public, anon, authenticated;
drop trigger if exists recipe_quantity_snapshot_capture on public.recipe_ingredients;
create trigger recipe_quantity_snapshot_capture
before delete on public.recipe_ingredients
for each row execute function private.capture_recipe_quantity_snapshot();

create or replace function private.restore_recipe_quantity_snapshot()
returns trigger language plpgsql security definer set search_path = ''
as $body$
declare s public.recipe_quantity_snapshot_restore%rowtype;
begin
    if new.display_amount is not null or new.display_unit_code is not null
        or new.base_per_display_unit is not null then return new; end if;
    select * into s from public.recipe_quantity_snapshot_restore
    where household_id = new.household_id
      and recipe_id = new.recipe_id
      and ingredient_id = new.ingredient_id
      and captured_at > now() - interval '10 minutes';
    if found and abs(s.amount_base - new.amount_base) <= greatest(
        0.000001::numeric, abs(new.amount_base) * 0.00000001::numeric
    ) then
        update public.recipe_ingredients set
            display_amount = s.display_amount,
            display_unit_code = s.display_unit_code,
            base_per_display_unit = s.base_per_display_unit,
            conversion_approximate = s.conversion_approximate
        where recipe_id = new.recipe_id and ingredient_id = new.ingredient_id
          and household_id = new.household_id;
    end if;
    return new;
end;
$body$;
revoke all on function private.restore_recipe_quantity_snapshot() from public, anon, authenticated;
drop trigger if exists recipe_quantity_snapshot_restore_after_insert on public.recipe_ingredients;
create trigger recipe_quantity_snapshot_restore_after_insert
after insert on public.recipe_ingredients
for each row execute function private.restore_recipe_quantity_snapshot();

-- Physical pantry lots. Existing pantry_items remains the compatibility view
-- of the same stock; it is NOT added to the lots when calculating totals.
create table if not exists public.pantry_lots (
    id uuid primary key default gen_random_uuid(),
    household_id uuid not null,
    ingredient_id uuid not null,
    product_id text,
    purchase_event_key text,
    original_amount_base numeric not null
        check (original_amount_base > 0 and original_amount_base <= 10000000000),
    remaining_amount_base numeric not null
        check (remaining_amount_base >= 0 and remaining_amount_base <= original_amount_base),
    opened boolean not null default false,
    legacy_unknown boolean not null default false,
    best_before date,
    lot_version bigint not null default 1 check (lot_version > 0),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    foreign key (ingredient_id, household_id)
        references public.ingredients(id, household_id) on delete cascade
);
create index if not exists pantry_lots_by_ingredient
    on public.pantry_lots(household_id, ingredient_id, opened desc, best_before, created_at);
create unique index if not exists pantry_lots_one_legacy_unknown
    on public.pantry_lots(household_id, ingredient_id)
    where legacy_unknown;
create unique index if not exists pantry_lots_purchase_event_unique
    on public.pantry_lots(household_id, purchase_event_key)
    where purchase_event_key is not null;
alter table public.pantry_lots enable row level security;
revoke all on public.pantry_lots from anon;
grant select, insert, update, delete on public.pantry_lots to authenticated;
drop policy if exists pantry_lots_select on public.pantry_lots;
create policy pantry_lots_select on public.pantry_lots for select to authenticated
    using (private.is_household_member(household_id));
drop policy if exists pantry_lots_insert on public.pantry_lots;
create policy pantry_lots_insert on public.pantry_lots for insert to authenticated
    with check (private.is_household_member(household_id));
drop policy if exists pantry_lots_update on public.pantry_lots;
create policy pantry_lots_update on public.pantry_lots for update to authenticated
    using (private.is_household_member(household_id))
    with check (private.is_household_member(household_id));
drop policy if exists pantry_lots_delete on public.pantry_lots;
create policy pantry_lots_delete on public.pantry_lots for delete to authenticated
    using (private.is_household_member(household_id));

-- Snapshot pre-existing balances without inventing package contents.
insert into public.pantry_lots (
    household_id, ingredient_id, original_amount_base, remaining_amount_base,
    opened, legacy_unknown
)
select household_id, ingredient_id, amount_base, amount_base, true, true
from public.pantry_items where amount_base > 0
on conflict (household_id, ingredient_id) where legacy_unknown do nothing;

-- Physical lot mutations maintain the old pantry_items total for old clients.
-- Nested invocations from old-client pantry writes are deliberately excluded.
create or replace function private.sync_pantry_total_from_lots()
returns trigger language plpgsql security definer set search_path = ''
as $body$
declare h uuid; i uuid; total numeric;
begin
    if pg_trigger_depth() > 1 then return null; end if;
    h := case when tg_op = 'DELETE' then old.household_id else new.household_id end;
    i := case when tg_op = 'DELETE' then old.ingredient_id else new.ingredient_id end;
    select coalesce(sum(remaining_amount_base), 0) into total
        from public.pantry_lots
        where household_id = h and ingredient_id = i;
    insert into public.pantry_items(household_id, ingredient_id, amount_base)
        values(h, i, total)
        on conflict(household_id, ingredient_id)
        do update set amount_base = excluded.amount_base, updated_at = now();
    return null;
end;
$body$;
revoke all on function private.sync_pantry_total_from_lots() from public, anon, authenticated;
drop trigger if exists pantry_lots_sync_total on public.pantry_lots;
create trigger pantry_lots_sync_total after insert or update or delete
on public.pantry_lots for each row execute function private.sync_pantry_total_from_lots();

-- An older client writes the aggregate directly. Attribute only the delta
-- to its legacy-unknown lot. Never take physical stock away from known lots.
create or replace function private.reconcile_old_pantry_write()
returns trigger language plpgsql security definer set search_path = ''
as $body$
declare total numeric; delta numeric; legacy public.pantry_lots%rowtype;
begin
    if pg_trigger_depth() > 1 then return new; end if;
    select coalesce(sum(remaining_amount_base), 0) into total
        from public.pantry_lots where household_id = new.household_id
          and ingredient_id = new.ingredient_id;
    delta := new.amount_base - total;
    if delta = 0 then return new; end if;
    select * into legacy from public.pantry_lots
        where household_id = new.household_id
          and ingredient_id = new.ingredient_id and legacy_unknown
        for update;
    if not found then
        if delta < 0 then
            raise exception 'Cannot consume a known package with a legacy total-only write';
        end if;
        insert into public.pantry_lots (
            household_id, ingredient_id, original_amount_base,
            remaining_amount_base, opened, legacy_unknown
        ) values(new.household_id, new.ingredient_id, delta, delta, true, true);
    elsif delta > 0 then
        update public.pantry_lots set
            original_amount_base = original_amount_base + delta,
            remaining_amount_base = remaining_amount_base + delta,
            lot_version = lot_version + 1,
            updated_at = now()
        where id = legacy.id;
    elsif legacy.remaining_amount_base + delta >= 0 then
        update public.pantry_lots set
            remaining_amount_base = remaining_amount_base + delta,
            lot_version = lot_version + 1,
            updated_at = now()
        where id = legacy.id;
    else
        raise exception 'Legacy total-only write would consume identified unopened package';
    end if;
    return new;
end;
$body$;
revoke all on function private.reconcile_old_pantry_write() from public, anon, authenticated;
drop trigger if exists pantry_items_legacy_reconcile on public.pantry_items;
create trigger pantry_items_legacy_reconcile
after insert or update of amount_base on public.pantry_items
for each row execute function private.reconcile_old_pantry_write();

-- Consumption is one atomic transaction, preferring opened/expiring lots.
-- Direct UPDATE is guarded by RLS; this RPC also checks household membership.
create or replace function public.consume_pantry_lots(
    p_household_id uuid, p_ingredient_id uuid, p_amount_base numeric
) returns numeric language plpgsql security invoker set search_path = ''
as $body$
declare lot record; remaining numeric; take_amount numeric;
begin
    if not private.is_household_member(p_household_id) then raise exception 'not a member'; end if;
    if p_amount_base is null or p_amount_base <= 0 or p_amount_base > 10000000000
        then raise exception 'invalid consumption'; end if;
    remaining := p_amount_base;
    for lot in
        select id, remaining_amount_base from public.pantry_lots
        where household_id = p_household_id
          and ingredient_id = p_ingredient_id and remaining_amount_base > 0
        order by opened desc, best_before asc nulls last, created_at, id
        for update
    loop
        take_amount := least(lot.remaining_amount_base, remaining);
        update public.pantry_lots
        set remaining_amount_base = remaining_amount_base - take_amount,
            opened = true, lot_version = lot_version + 1, updated_at = now()
        where id = lot.id;
        remaining := remaining - take_amount;
        exit when remaining = 0;
    end loop;
    if remaining > 0 then raise exception 'insufficient pantry stock'; end if;
    return p_amount_base;
end;
$body$;
revoke all on function public.consume_pantry_lots(uuid,uuid,numeric) from public, anon;
grant execute on function public.consume_pantry_lots(uuid,uuid,numeric) to authenticated;
