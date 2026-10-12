-- Run only against a staging database after the 170000 migration.
-- The entire fixture, including receipt rows, is rolled back.
begin;
do $multi_ingredient_meal$
declare
    h uuid := gen_random_uuid();
    i1 uuid := gen_random_uuid();
    i2 uuid := gen_random_uuid();
    recipe_id uuid := gen_random_uuid();
    event_id uuid := gen_random_uuid();
    owner_id uuid;
    portion1 numeric;
    portion2 numeric;
    failed boolean := false;
    meal_day date := (now() at time zone 'Europe/Copenhagen')::date;
begin
    select id into owner_id from auth.users limit 1;
    if owner_id is null then
        raise notice 'No auth user available; skipping multi-ingredient test';
        return;
    end if;
    insert into public.households(id,name,join_code,created_by)
    values(h,'Temporary atomic meal test',
        substring(md5(gen_random_uuid()::text) from 1 for 8),owner_id);
    insert into public.household_members(household_id,user_id,role)
        values(h,owner_id,'owner') on conflict do nothing;
    perform set_config('request.jwt.claim.sub',owner_id::text,true);
    insert into public.ingredients(id,household_id,name,quantity_type,preferred_unit)
        values (i1,h,'Temporary ham','MASS','GRAM'),
               (i2,h,'Temporary rice','MASS','GRAM');
    insert into public.recipes(id,household_id,name,meal_type,notes)
        values (recipe_id,h,'Temporary dinner','DINNER','');
    insert into public.pantry_lots(
        household_id,ingredient_id,original_amount_base,remaining_amount_base,opened
    ) values (h,i1,1200,1200,true),(h,i2,200,200,true);

    perform public.complete_meal_with_lots_once(
        h,meal_day,recipe_id,jsonb_build_object(i1::text,300,i2::text,50),event_id
    );
    -- Retry same event/contents: must not spend a second time.
    perform public.complete_meal_with_lots_once(
        h,meal_day,recipe_id,jsonb_build_object(i1::text,300,i2::text,50),event_id
    );
    select sum(remaining_amount_base) into portion1 from public.pantry_lots
        where household_id=h and ingredient_id=i1;
    select sum(remaining_amount_base) into portion2 from public.pantry_lots
        where household_id=h and ingredient_id=i2;
    if portion1 <> 900 or portion2 <> 150 then
        raise exception 'Atomic dinner used incorrect package amounts: %, %',portion1,portion2;
    end if;
    if (select count(*) from public.meal_history
            where household_id=h and eaten_on=meal_day) <> 1 then
        raise exception 'Meal history was not inserted exactly once';
    end if;

    -- Insufficient stock for the second ingredient must undo all deductions
    -- from earlier ingredients, not leave partial inventory after failure.
    begin
        perform public.complete_meal_with_lots_once(
            h,meal_day - 1,recipe_id,
            jsonb_build_object(i1::text,100,i2::text,999),
            gen_random_uuid()
        );
    exception when others then failed := true;
    end;
    if not failed then raise exception 'Insufficient-stock meal succeeded'; end if;
    select sum(remaining_amount_base) into portion1 from public.pantry_lots
        where household_id=h and ingredient_id=i1;
    select sum(remaining_amount_base) into portion2 from public.pantry_lots
        where household_id=h and ingredient_id=i2;
    if portion1 <> 900 or portion2 <> 150 then
        raise exception 'Failed dinner modified package amounts: %, %',portion1,portion2;
    end if;
    if exists(select 1 from public.meal_history
        where household_id=h and eaten_on=meal_day - 1) then
        raise exception 'Failed meal was still entered in history';
    end if;
    raise notice 'Multi-ingredient meal is atomic and replay-safe';
end;
$multi_ingredient_meal$;
rollback;
