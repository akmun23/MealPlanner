-- Run only against a staging database after the shopping_extras migration.
-- Checks the table contract the Android upsert/delete relies on, then rolls
-- back every fixture row.
begin;
do $extras_contract$
declare
    h uuid := gen_random_uuid();
    w uuid := gen_random_uuid();
    i uuid := gen_random_uuid();
    owner_id uuid;
    stored numeric;
    failed boolean;
begin
    select id into owner_id from auth.users limit 1;
    if owner_id is null then
        raise notice 'No auth user to seed foreign keys; skipping test';
        return;
    end if;
    insert into public.households(id,name,join_code,created_by)
        values (h,'Temporary extras test',substring(md5(h::text) from 1 for 8),owner_id);
    insert into public.weekly_plans(id,household_id,week_start)
        values (w,h,'2040-01-02');
    insert into public.ingredients(id,household_id,name,quantity_type,preferred_unit)
        values (i,h,'Temporary milk','VOLUME','LITRE');

    -- Insert, then the Android upsert (on conflict) replaces the amount.
    insert into public.shopping_extras(household_id,weekly_plan_id,ingredient_id,extra_amount_base)
        values (h,w,i,500);
    insert into public.shopping_extras(household_id,weekly_plan_id,ingredient_id,extra_amount_base)
        values (h,w,i,1500)
    on conflict (weekly_plan_id,ingredient_id)
    do update set extra_amount_base = excluded.extra_amount_base;
    select extra_amount_base into stored
        from public.shopping_extras where weekly_plan_id = w and ingredient_id = i;
    if stored is distinct from 1500 then
        raise exception 'upsert did not replace the extra amount (got %)', stored;
    end if;

    -- Zero, negative and absurd amounts are rejected; removal is a delete.
    failed := false;
    begin
        update public.shopping_extras set extra_amount_base = 0
            where weekly_plan_id = w and ingredient_id = i;
    exception when check_violation then failed := true;
    end;
    if not failed then raise exception 'zero extra amount was accepted'; end if;

    failed := false;
    begin
        update public.shopping_extras set extra_amount_base = 2000000000
            where weekly_plan_id = w and ingredient_id = i;
    exception when check_violation then failed := true;
    end;
    if not failed then raise exception 'absurd extra amount was accepted'; end if;

    -- An extra must belong to an ingredient of the same household.
    failed := false;
    begin
        insert into public.shopping_extras(household_id,weekly_plan_id,ingredient_id,extra_amount_base)
            values (gen_random_uuid(),w,i,100);
    exception when foreign_key_violation then failed := true;
    end;
    if not failed then raise exception 'cross-household extra was accepted'; end if;

    -- Deleting the week removes its extras.
    delete from public.weekly_plans where id = w;
    if exists (select 1 from public.shopping_extras where weekly_plan_id = w) then
        raise exception 'extras survived their weekly plan';
    end if;

    raise notice 'shopping_extras contract passed';
end;
$extras_contract$;
rollback;
