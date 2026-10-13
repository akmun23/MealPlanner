-- Regression: default-valued booleans omitted from JSON recordsets
-- are turned into SQL NULL by PostgREST bulk upserts. Test both the INSERT
-- and ON CONFLICT paths, then roll back all fixture data.
begin;
do $sync_contract$
declare
    h uuid := gen_random_uuid();
    i uuid := gen_random_uuid();
    r uuid := gen_random_uuid();
    owner_id uuid;
    stored boolean;
    amount numeric;
begin
    select id into owner_id from auth.users limit 1;
    if owner_id is null then
        raise notice 'No auth user to seed foreign keys; skipping test';
        return;
    end if;
    insert into public.households(id,name,join_code,created_by)
        values (h,'Temporary recipe sync',substring(md5(h::text) from 1 for 8),owner_id);
    insert into public.ingredients(id,household_id,name,quantity_type,preferred_unit)
        values(i,h,'Temporary spice','MASS','GRAM');
    insert into public.recipes(id,household_id,name,meal_type,notes)
        values(r,h,'Temporary recipe','DINNER','');
    insert into public.recipe_ingredients(
        household_id,recipe_id,ingredient_id,amount_base,conversion_approximate
    ) values(h,r,i,15,null);
    select amount_base,conversion_approximate into amount,stored
        from public.recipe_ingredients where recipe_id=r and ingredient_id=i;
    if stored is distinct from false or amount <> 15 then
        raise exception 'NULL estimate INSERT did not preserve amount and normalize false';
    end if;
    insert into public.recipe_ingredients(
        household_id,recipe_id,ingredient_id,amount_base,conversion_approximate
    ) values(h,r,i,20,null)
    on conflict(recipe_id,ingredient_id) do update
        set amount_base=excluded.amount_base,
            conversion_approximate=excluded.conversion_approximate;
    select amount_base,conversion_approximate into amount,stored
        from public.recipe_ingredients where recipe_id=r and ingredient_id=i;
    if stored is distinct from false or amount <> 20 then
        raise exception 'NULL estimate UPSERT did not preserve amount and normalize false';
    end if;
    raise notice 'PostgREST NULL estimate insert/upsert compatibility passed';
end;
$sync_contract$;
rollback;
