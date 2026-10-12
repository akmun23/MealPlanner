-- Staging-only regression. Must stay inside BEGIN/ROLLBACK.
-- A pre-upgrade APK can delete/reinsert recipe_ingredients on ordinary edits;
-- unchanged physical requirements must retain the newer frozen display text.
begin;
do $old_client_recipe$
declare
    h uuid := gen_random_uuid();
    i uuid := gen_random_uuid();
    r uuid := gen_random_uuid();
    owner_id uuid;
    restored_amount double precision;
    restored_unit text;
    restored_factor double precision;
begin
    select id into owner_id from auth.users limit 1;
    if owner_id is null then
        raise notice 'No auth fixture user; skipping old-client recipe contract';
        return;
    end if;

    insert into public.households(id,name,join_code,created_by)
        values (h,'Temporary recipe snapshot',
            substring(md5(gen_random_uuid()::text) from 1 for 8),owner_id);
    insert into public.ingredients(
        id,household_id,name,quantity_type,preferred_unit
    ) values (i,h,'Temporary ham','MASS','GRAM');
    insert into public.recipes(id,household_id,name,meal_type,notes)
        values (r,h,'Temporary sandwich','DINNER','');

    insert into public.recipe_ingredients(
        household_id,recipe_id,ingredient_id,amount_base,
        display_amount,display_unit_code,base_per_display_unit
    ) values (h,r,i,200,1,'PACK',200);

    -- Simulate an older client saving only canonical physical fields.
    delete from public.recipe_ingredients where recipe_id=r and ingredient_id=i;
    insert into public.recipe_ingredients(
        household_id,recipe_id,ingredient_id,amount_base
    ) values (h,r,i,200);
    select display_amount,display_unit_code,base_per_display_unit
        into restored_amount,restored_unit,restored_factor
    from public.recipe_ingredients where recipe_id=r and ingredient_id=i;
    if restored_amount <> 1 or restored_unit <> 'PACK' or restored_factor <> 200 then
        raise exception 'Old-client metadata edit destroyed frozen recipe display';
    end if;

    -- Physical quantity really changed: restoring the old text would lie.
    delete from public.recipe_ingredients where recipe_id=r and ingredient_id=i;
    insert into public.recipe_ingredients(
        household_id,recipe_id,ingredient_id,amount_base
    ) values (h,r,i,150);
    select display_amount,display_unit_code
        into restored_amount,restored_unit
    from public.recipe_ingredients where recipe_id=r and ingredient_id=i;
    if restored_amount is not null or restored_unit is not null then
        raise exception 'Old-client physical quantity edit reused wrong snapshot';
    end if;
    raise notice 'Older APK display snapshot contract passed';
end;
$old_client_recipe$;
rollback;
