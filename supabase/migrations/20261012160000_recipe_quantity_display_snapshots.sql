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
    -- Older clients edit only the aggregate. Debit real packages in the
    -- same deterministic way as a modern client; never allow negative lots.
    if delta < 0 then
        perform public.consume_pantry_lots(new.household_id,new.ingredient_id,-delta);
        return new;
    end if;
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


-- Exactly-once lot consumption. A repeated WorkManager delivery of the
-- same household transaction token returns the original result.
create table if not exists public.pantry_lot_consumptions (
    transaction_key uuid not null,
    household_id uuid not null,
    ingredient_id uuid not null,
    amount_base numeric not null check (amount_base > 0),
    created_at timestamptz not null default now(),
    primary key (household_id, transaction_key, ingredient_id)
);
revoke all on public.pantry_lot_consumptions from public, anon, authenticated;
alter table public.pantry_lot_consumptions enable row level security;
create or replace function public.consume_pantry_lots_once(
    p_household_id uuid, p_ingredient_id uuid,
    p_amount_base numeric, p_transaction_key uuid
) returns numeric language plpgsql security definer set search_path = ''
as $body$
declare prior numeric;
begin
    if not private.is_household_member(p_household_id) then
        raise exception 'not a household member';
    end if;
    if p_transaction_key is null or p_amount_base is null
       or p_amount_base <= 0 or p_amount_base > 10000000000
       then raise exception 'invalid amount or idempotency key'; end if;
    insert into public.pantry_lot_consumptions(
        transaction_key, household_id, ingredient_id, amount_base
    ) values (p_transaction_key, p_household_id, p_ingredient_id, p_amount_base)
    on conflict do nothing;
    if not found then
        select amount_base into prior from public.pantry_lot_consumptions
        where transaction_key = p_transaction_key
          and household_id = p_household_id
          and ingredient_id = p_ingredient_id;
        if prior is distinct from p_amount_base then
            raise exception 'reused transaction with different amount';
        end if;
        return prior;
    end if;
    -- The atomic consumption RPC raises on insufficient stock. In that case
    -- the just-inserted receipt is rolled back in the same transaction.
    perform public.consume_pantry_lots(p_household_id, p_ingredient_id, p_amount_base);
    return p_amount_base;
end;
$body$;
revoke all on function public.consume_pantry_lots_once(uuid,uuid,numeric,uuid)
    from public, anon;
grant execute on function public.consume_pantry_lots_once(uuid,uuid,numeric,uuid)
    to authenticated;

-- Exactly-once purchase ID independent of week/ingredient: allow multiple
-- intentional purchases during the same week but not a duplicated retry.
create table if not exists public.pantry_purchase_events (
    id uuid primary key,
    household_id uuid not null,
    weekly_plan_id uuid not null,
    ingredient_id uuid not null,
    product_id text,
    manual_product_name text,
    package_count integer,
    purchased_amount_base numeric not null
        check (purchased_amount_base > 0 and purchased_amount_base <= 10000000),
    paid_total_ore bigint,
    created_at timestamptz not null default now(),
    foreign key (ingredient_id, household_id)
        references public.ingredients(id, household_id) on delete cascade
);
revoke all on public.pantry_purchase_events from public, anon, authenticated;
alter table public.pantry_purchase_events enable row level security;

-- Legacy edge clients still call the original one-per-week SQL endpoint.
-- Preserve its contract but turn its stock credit into concrete lot(s)
-- rather than independently adding to the old pantry_items counter.
create or replace function public.bilka_record_purchase(
 p_household_id uuid, p_weekly_plan_id uuid, p_ingredient_id uuid,
 p_product_id text, p_manual_product_name text, p_package_count integer,
 p_purchased_amount_base numeric, p_paid_total_ore bigint
) returns numeric language plpgsql security definer set search_path = ''
as $body$
declare v_added numeric; v_packs integer; v_n integer; v_piece numeric;
begin
 if (p_product_id is null) = (p_manual_product_name is null)
    then raise exception 'product selection required'; end if;
 if p_purchased_amount_base is null or p_purchased_amount_base <= 0
    or p_purchased_amount_base > 10000000 then raise exception 'invalid amount'; end if;
 if p_package_count is not null and (p_package_count < 1 or p_package_count > 500)
    then raise exception 'invalid packages'; end if;
 if p_product_id is not null and p_package_count is null
    then raise exception 'package count required'; end if;
 if p_paid_total_ore is not null and (p_paid_total_ore < 0 or p_paid_total_ore > 1000000000)
    then raise exception 'invalid price'; end if;
 if not exists(select 1 from public.weekly_plans
    where id=p_weekly_plan_id and household_id=p_household_id)
    then raise exception 'unknown week'; end if;
 if not exists(select 1 from public.ingredients
    where id=p_ingredient_id and household_id=p_household_id)
    then raise exception 'unknown ingredient'; end if;
 insert into public.shopping_product_overrides(
    household_id,weekly_plan_id,ingredient_id,product_id,manual_product_name,
    package_count,purchased_amount_base,paid_total_ore
 ) values(
    p_household_id,p_weekly_plan_id,p_ingredient_id,p_product_id,
    p_manual_product_name,p_package_count,p_purchased_amount_base,p_paid_total_ore
 ) on conflict(weekly_plan_id,ingredient_id) do update set
    product_id=excluded.product_id,
    manual_product_name=excluded.manual_product_name,
    package_count=excluded.package_count,
    purchased_amount_base=excluded.purchased_amount_base,
    paid_total_ore=excluded.paid_total_ore,updated_at=now()
    where public.shopping_product_overrides.purchased_amount_base is null
 returning purchased_amount_base into v_added;
 if v_added is null then raise exception 'already recorded'; end if;
 v_packs := coalesce(p_package_count, 1);
 for v_n in 1..v_packs loop
    v_piece := case when v_n = v_packs then
        v_added - (v_added / v_packs) * (v_packs - 1)
        else v_added / v_packs end;
    insert into public.pantry_lots(
        household_id, ingredient_id, product_id, purchase_event_key,
        original_amount_base, remaining_amount_base
    ) values (
        p_household_id, p_ingredient_id, p_product_id,
        'legacy-bilka:' || p_weekly_plan_id || ':' || p_ingredient_id || ':' || v_n,
        v_piece, v_piece
    );
 end loop;
 return v_added;
end;
$body$;
revoke all on function public.bilka_record_purchase(uuid,uuid,uuid,text,text,integer,numeric,bigint)
 from public,anon,authenticated;
grant execute on function public.bilka_record_purchase(uuid,uuid,uuid,text,text,integer,numeric,bigint)
 to service_role;

create or replace function public.bilka_record_purchase_v2(
 p_purchase_id uuid, p_household_id uuid, p_weekly_plan_id uuid, p_ingredient_id uuid,
 p_product_id text, p_manual_product_name text, p_package_count integer,
 p_purchased_amount_base numeric, p_paid_total_ore bigint
) returns numeric language plpgsql security definer set search_path = ''
as $body$
declare prior public.pantry_purchase_events%rowtype;
        n integer; packs integer; piece numeric;
begin
 if p_purchase_id is null then raise exception 'purchase identifier required'; end if;
 if (p_product_id is null) = (p_manual_product_name is null)
    then raise exception 'product selection required'; end if;
 if p_purchased_amount_base is null or p_purchased_amount_base <= 0
    or p_purchased_amount_base > 10000000 then raise exception 'invalid amount'; end if;
 if p_package_count is not null and (p_package_count < 1 or p_package_count > 500)
    then raise exception 'invalid packages'; end if;
 if p_product_id is not null and p_package_count is null
    then raise exception 'package count required'; end if;
 if p_paid_total_ore is not null and (p_paid_total_ore < 0 or p_paid_total_ore > 1000000000)
    then raise exception 'invalid price'; end if;
 if not exists(select 1 from public.weekly_plans
    where id=p_weekly_plan_id and household_id=p_household_id)
    then raise exception 'unknown week'; end if;
 if not exists(select 1 from public.ingredients
    where id=p_ingredient_id and household_id=p_household_id)
    then raise exception 'unknown ingredient'; end if;
 insert into public.pantry_purchase_events(
    id, household_id, weekly_plan_id, ingredient_id, product_id,
    manual_product_name, package_count, purchased_amount_base, paid_total_ore
 ) values (
    p_purchase_id,p_household_id,p_weekly_plan_id,p_ingredient_id,
    p_product_id,p_manual_product_name,p_package_count,p_purchased_amount_base,p_paid_total_ore
 ) on conflict (id) do nothing;
 if not found then
    select * into prior from public.pantry_purchase_events where id=p_purchase_id;
    if prior.household_id is distinct from p_household_id
       or prior.weekly_plan_id is distinct from p_weekly_plan_id
       or prior.ingredient_id is distinct from p_ingredient_id
       or prior.product_id is distinct from p_product_id
       or prior.manual_product_name is distinct from p_manual_product_name
       or prior.package_count is distinct from p_package_count
       or prior.purchased_amount_base is distinct from p_purchased_amount_base
       then raise exception 'purchase identifier reused with different content'; end if;
    return prior.purchased_amount_base;
 end if;
 -- Maintain compatibility with last-selected purchase shown in the shopping UI.
 insert into public.shopping_product_overrides(
    household_id,weekly_plan_id,ingredient_id,product_id,manual_product_name,
    package_count,purchased_amount_base,paid_total_ore
 ) values (
    p_household_id,p_weekly_plan_id,p_ingredient_id,p_product_id,
    p_manual_product_name,p_package_count,p_purchased_amount_base,p_paid_total_ore
 ) on conflict(weekly_plan_id,ingredient_id) do update set
    product_id=excluded.product_id,
    manual_product_name=excluded.manual_product_name,
    package_count=excluded.package_count,
    purchased_amount_base=excluded.purchased_amount_base,
    paid_total_ore=excluded.paid_total_ore,updated_at=now();
 packs := coalesce(p_package_count, 1);
 for n in 1..packs loop
    piece := case when n=packs then p_purchased_amount_base -
        (p_purchased_amount_base / packs) * (packs - 1)
        else p_purchased_amount_base / packs end;
    insert into public.pantry_lots(
        household_id,ingredient_id,product_id,purchase_event_key,
        original_amount_base,remaining_amount_base
    ) values (
        p_household_id,p_ingredient_id,p_product_id,
        'v2-bilka:' || p_purchase_id || ':' || n,
        piece,piece
    );
 end loop;
 return p_purchased_amount_base;
end;
$body$;
revoke all on function public.bilka_record_purchase_v2(uuid,uuid,uuid,uuid,text,text,integer,numeric,bigint)
 from public,anon,authenticated;
grant execute on function public.bilka_record_purchase_v2(uuid,uuid,uuid,uuid,text,text,integer,numeric,bigint)
 to service_role;


-- Version-checked, idempotent lot insert/update. No stale device may
-- overwrite newer package remainder. Receipts prevent replayed writes.
create table if not exists public.pantry_lot_mutation_receipts (
    event_id uuid primary key,
    household_id uuid not null,
    lot_id uuid not null,
    expected_version bigint not null,
    resulting_version bigint not null,
    created_at timestamptz not null default now()
);
revoke all on public.pantry_lot_mutation_receipts from public,anon,authenticated;
alter table public.pantry_lot_mutation_receipts enable row level security;

create or replace function public.upsert_pantry_lot_once(
 p_household_id uuid, p_lot_id uuid, p_ingredient_id uuid,
 p_original_amount_base numeric, p_remaining_amount_base numeric,
 p_opened boolean, p_legacy_unknown boolean, p_product_id text,
 p_best_before text, p_expected_version bigint, p_event_id uuid
) returns bigint language plpgsql security definer set search_path = ''
as $body$
declare previous public.pantry_lot_mutation_receipts%rowtype;
        new_version bigint;
begin
 if not private.is_household_member(p_household_id) then raise exception 'not a member'; end if;
 if p_event_id is null or p_lot_id is null or p_ingredient_id is null
    or p_original_amount_base is null or p_original_amount_base <= 0
    or p_original_amount_base > 10000000000
    or p_remaining_amount_base is null or p_remaining_amount_base < 0
    or p_remaining_amount_base > p_original_amount_base
    or p_expected_version is null or p_expected_version < 0
    then raise exception 'invalid lot mutation'; end if;
 select * into previous from public.pantry_lot_mutation_receipts where event_id=p_event_id;
 if found then
    if previous.household_id <> p_household_id or previous.lot_id <> p_lot_id
       or previous.expected_version <> p_expected_version then
        raise exception 'lot mutation token reused';
    end if;
    return previous.resulting_version;
 end if;

 if p_expected_version = 0 then
    insert into public.pantry_lots(
        id, household_id, ingredient_id, original_amount_base,
        remaining_amount_base, opened, legacy_unknown, product_id, best_before
    ) values (
        p_lot_id,p_household_id,p_ingredient_id,p_original_amount_base,
        p_remaining_amount_base,coalesce(p_opened,false),
        coalesce(p_legacy_unknown,false),nullif(p_product_id,''),
        nullif(p_best_before,'')::date
    ) on conflict(id) do nothing
    returning lot_version into new_version;
 else
    update public.pantry_lots set
        remaining_amount_base = p_remaining_amount_base,
        opened = coalesce(p_opened,false),
        best_before = nullif(p_best_before,'')::date,
        lot_version = lot_version + 1, updated_at = now()
    where id = p_lot_id and household_id = p_household_id
      and ingredient_id = p_ingredient_id and lot_version = p_expected_version
      and original_amount_base = p_original_amount_base
      and legacy_unknown = coalesce(p_legacy_unknown,false)
    returning lot_version into new_version;
 end if;
 if new_version is null then
    raise exception 'stale pantry lot version; refresh before editing';
 end if;
 insert into public.pantry_lot_mutation_receipts (
    event_id, household_id, lot_id, expected_version, resulting_version
 ) values (p_event_id,p_household_id,p_lot_id,p_expected_version,new_version);
 return new_version;
end;
$body$;
revoke all on function public.upsert_pantry_lot_once(uuid,uuid,uuid,numeric,numeric,boolean,boolean,text,text,bigint,uuid)
 from public,anon;
grant execute on function public.upsert_pantry_lot_once(uuid,uuid,uuid,numeric,numeric,boolean,boolean,text,text,bigint,uuid)
 to authenticated;
