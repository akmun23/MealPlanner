-- Regression for a household correction, inside an automatic rollback.
-- Nothing is written permanently to live household data.
begin;
do $corrected_measure$
declare
    h uuid := gen_random_uuid();
    i uuid := gen_random_uuid();
    owner_id uuid;
    spoon_recipe uuid := gen_random_uuid();
    pack_recipe uuid := gen_random_uuid();
    legacy_recipe uuid := gen_random_uuid();
    receipt uuid := gen_random_uuid();
    v bigint;
    spoon_base numeric;
    spoon_amount double precision;
    spoon_factor double precision;
    pack_base numeric;
    legacy_base numeric;
    stale_rejected boolean := false;
begin
    select id into owner_id from auth.users limit 1;
    if owner_id is null then
        raise notice 'Missing auth fixture user; skipping conversion contract';
        return;
    end if;
    insert into public.households(id,name,join_code,created_by)
    values(h,'Temporary conversion correction',substring(md5(h::text) from 1 for 8),owner_id);
    insert into public.household_members(household_id,user_id,role)
    values(h,owner_id,'owner') on conflict do nothing;
    perform set_config('request.jwt.claim.sub',owner_id::text,true);
    insert into public.ingredients(id,household_id,name,quantity_type,preferred_unit)
    values(i,h,'Temporary spice','MASS','GRAM');
    v := public.save_ingredient_conversions_once(
        h,i,0,'[
            {"unit_code":"TABLESPOON","base_amount_per_unit":6,"approximate":true,"source":"household-confirmed"},
            {"unit_code":"PACK","base_amount_per_unit":200,"approximate":false,"source":"household-confirmed"}
        ]'::jsonb,gen_random_uuid()
    );
    if v <> 1 then raise exception 'Initial conversion CAS failed'; end if;

    insert into public.recipes(id,household_id,name,meal_type,notes)
    values
      (spoon_recipe,h,'Temporary spoon recipe','DINNER',''),
      (pack_recipe,h,'Temporary package recipe','DINNER',''),
      (legacy_recipe,h,'Temporary old recipe','DINNER','');
    insert into public.recipe_ingredients(
        household_id,recipe_id,ingredient_id,amount_base,
        display_amount,display_unit_code,base_per_display_unit,conversion_approximate
    ) values
      (h,spoon_recipe,i,12,2,'TABLESPOON',6,true),
      (h,pack_recipe,i,200,1,'PACK',200,false),
      (h,legacy_recipe,i,20,null,null,null,false);

    v := public.save_ingredient_conversions_once(
        h,i,1,'[
            {"unit_code":"TABLESPOON","base_amount_per_unit":5,"approximate":false,"source":"household-confirmed"},
            {"unit_code":"PACK","base_amount_per_unit":150,"approximate":false,"source":"household-confirmed"}
        ]'::jsonb,receipt
    );
    if v <> 2 then raise exception 'Profile correction CAS failed'; end if;
    select amount_base,display_amount,base_per_display_unit into
        spoon_base,spoon_amount,spoon_factor
        from public.recipe_ingredients where recipe_id=spoon_recipe;
    select amount_base into pack_base from public.recipe_ingredients where recipe_id=pack_recipe;
    select amount_base into legacy_base from public.recipe_ingredients where recipe_id=legacy_recipe;
    if spoon_base <> 10 or spoon_amount <> 2 or spoon_factor <> 5 then
        raise exception 'Spoon definition did not correct saved physical recipe';
    end if;
    if pack_base <> 200 or legacy_base <> 20 then
        raise exception 'Historic package or legacy physical recipe was rewritten';
    end if;
    if v <> public.save_ingredient_conversions_once(
        h,i,1,'[
            {"unit_code":"TABLESPOON","base_amount_per_unit":5,"approximate":false,"source":"household-confirmed"},
            {"unit_code":"PACK","base_amount_per_unit":150,"approximate":false,"source":"household-confirmed"}
        ]'::jsonb,receipt
    ) then raise exception 'Duplicate event was not idempotent'; end if;
    begin
        perform public.save_ingredient_conversions_once(
            h,i,1,'[]'::jsonb,gen_random_uuid()
        );
    exception when others then
        stale_rejected := true;
    end;
    if not stale_rejected then
        raise exception 'Old revision overwrote corrected household profiles';
    end if;
    raise notice 'Spoon factor correction, unchanged packs, legacy data and CAS replay verified';
end;
$corrected_measure$;
rollback;
