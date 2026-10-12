-- Execute ONLY after applying quantity migration to a clean isolated staging DB.
-- Never execute this fixture without BEGIN/ROLLBACK (or in production).
begin;
do $quantity_test$
declare
    h uuid := gen_random_uuid();
    i uuid := gen_random_uuid();
    w uuid := gen_random_uuid();
    owner_id uuid;
    purchase_id uuid := gen_random_uuid();
    base_before numeric;
    base_after numeric;
    replay numeric;
    lot_count integer;
    first_lot_id uuid;
    profile_id uuid := gen_random_uuid();
    profile_revision bigint;
    profile_stale_rejected boolean := false;
begin
    select id into owner_id from auth.users limit 1;
    if owner_id is null then
        raise notice 'No auth user: cannot seed household fixture; skipping';
        return;
    end if;
    insert into public.households(id, name, join_code, created_by)
        values (h, 'Temporary quantity test',
            substring(md5(gen_random_uuid()::text) from 1 for 8), owner_id);
    insert into public.household_members(household_id,user_id,role)
        values (h,owner_id,'owner')
        on conflict do nothing;
    perform set_config('request.jwt.claim.sub',owner_id::text,true);
    insert into public.weekly_plans(id, household_id, week_start)
        values (w, h, '2040-05-07');
    insert into public.ingredients(
        id, household_id, name, quantity_type, preferred_unit
    ) values (i, h, 'Temporary test pasta', 'MASS', 'GRAM');
    insert into public.pantry_items(household_id, ingredient_id, amount_base)
        values (h, i, 200);

    -- The old pantry_items INSERT trigger already creates the single
    -- legacy-unknown lot. Do not insert it again in this fixture.
    select sum(remaining_amount_base) into base_before from public.pantry_lots
        where household_id = h and ingredient_id = i;
    if base_before <> 200 then
        raise exception 'Legacy stock counted incorrectly: %',base_before;
    end if;

    -- One new physical package must raise both lot total and legacy aggregate.
    insert into public.pantry_lots(
        household_id, ingredient_id, original_amount_base,
        remaining_amount_base, opened
    ) values (h, i, 150, 150, false)
    returning id into first_lot_id;
    select amount_base into base_after from public.pantry_items
        where household_id = h and ingredient_id = i;
    if base_after <> 350 then
        raise exception 'Lot/aggregate divergence: %',base_after;
    end if;

    -- Old-client absolute aggregate decrement consumes opened stock before
    -- unopened purchases, while old+new stocks remain one physical total.
    update public.pantry_items set amount_base = 250
        where household_id = h and ingredient_id = i;
    select remaining_amount_base into base_after from public.pantry_lots
        where household_id = h and ingredient_id = i and legacy_unknown;
    if base_after <> 100 then
        raise exception 'Old-client decrement did not consume open legacy lot: %',base_after;
    end if;
    select remaining_amount_base into base_after from public.pantry_lots
        where id = first_lot_id;
    if base_after <> 150 then
        raise exception 'Unopened physical package was consumed too early: %',base_after;
    end if;

    -- The new checkout can record multiple intentional events for the same
    -- ingredient/week; one event ID cannot credit stock twice.
    perform public.bilka_record_purchase_v2(
        purchase_id,h,w,i,null,'Manuelt køb 150g',1,150,2495
    );
    replay := public.bilka_record_purchase_v2(
        purchase_id,h,w,i,null,'Manuelt køb 150g',1,150,2495
    );
    if replay <> 150 then raise exception 'Duplicate receipt replay mismatch'; end if;
    perform public.bilka_record_purchase_v2(
        gen_random_uuid(),h,w,i,null,'Manuelt køb 150g',1,150,2495
    );
    select count(*) into lot_count from public.pantry_purchase_events
        where household_id=h and ingredient_id=i;
    if lot_count <> 2 then
        raise exception 'Legitimate repeated purchase was blocked or retried twice: %',lot_count;
    end if;

    -- Household profile version 0 -> 1, replay -> 1, stale phone fails,
    -- second confirmed package factor moves to version 2.
    profile_revision := public.save_ingredient_conversions_once(
        h,i,0,
        '[{"unit_code":"PACK","base_amount_per_unit":200,"approximate":false}]'::jsonb,
        profile_id
    );
    if profile_revision <> 1 then raise exception 'Profile first revision not 1'; end if;
    profile_revision := public.save_ingredient_conversions_once(
        h,i,0,
        '[{"unit_code":"PACK","base_amount_per_unit":200,"approximate":false}]'::jsonb,
        profile_id
    );
    if profile_revision <> 1 then raise exception 'Profile replay changed version'; end if;
    begin
        perform public.save_ingredient_conversions_once(
            h,i,0,
            '[{"unit_code":"PACK","base_amount_per_unit":150,"approximate":false}]'::jsonb,
            gen_random_uuid()
        );
    exception when others then profile_stale_rejected := true;
    end;
    if not profile_stale_rejected then raise exception 'Stale device overwrote profile'; end if;
    profile_revision := public.save_ingredient_conversions_once(
        h,i,1,
        '[{"unit_code":"PACK","base_amount_per_unit":150,"approximate":false}]'::jsonb,
        gen_random_uuid()
    );
    if profile_revision <> 2 or not exists (
        select 1 from public.ingredient_unit_conversions
        where household_id=h and ingredient_id=i
          and unit_code='PACK' and base_amount_per_unit=150
    ) then
        raise exception 'Confirmed package size did not update atomically';
    end if;

    -- Unknown package factor must never be guessed or materialized.
    if exists (
        select 1 from public.ingredient_unit_conversions
        where ingredient_id = i and unit_code <> 'PACK'
    ) then raise exception 'An unconfirmed conversion unit appeared'; end if;

    select amount_base into base_after from public.pantry_items
        where household_id=h and ingredient_id=i;
    if base_after <> 550 then raise exception 'Duplicate stock or missing credit: %',base_after; end if;

    raise notice 'Quantity lot/purchase fixture passed';
end;
$quantity_test$;
rollback;
