-- Correct user-defined measuring units across all known recipe snapshots
-- in the SAME serializable household-profile revision transaction.
-- PACK/CAN remain historical physical snapshots even when shops change pack size.
-- Old recipes without a recorded display unit cannot be safely inferred.
create or replace function public.save_ingredient_conversions_once(
    p_household_id uuid, p_ingredient_id uuid, p_expected_revision bigint,
    p_profiles jsonb, p_event_id uuid
) returns bigint language plpgsql security definer set search_path = ''
as $body$
declare
    current_revision bigint;
    receipt public.ingredient_conversion_receipts%rowtype;
    digest_value text;
    profile jsonb;
    unit_code text;
    factor numeric;
    approximate boolean;
    provenance text;
    previous_factor numeric;
begin
    if not private.is_household_member(p_household_id) then
        raise exception 'not a household member';
    end if;
    if p_event_id is null or p_expected_revision is null or p_expected_revision < 0
       or p_profiles is null or jsonb_typeof(p_profiles) <> 'array'
       or jsonb_array_length(p_profiles) > 30 then
        raise exception 'invalid conversion profile request';
    end if;
    if not exists (
        select 1 from public.ingredients
        where household_id = p_household_id and id = p_ingredient_id
    ) then
        raise exception 'ingredient does not belong to household';
    end if;
    digest_value := md5(jsonb_build_object(
        'household',p_household_id,'ingredient',p_ingredient_id,
        'expected_revision',p_expected_revision,'profiles',p_profiles
    )::text);

    insert into public.ingredient_conversion_versions(
        household_id, ingredient_id, revision
    ) values(p_household_id,p_ingredient_id,0)
    on conflict do nothing;
    select revision into current_revision
    from public.ingredient_conversion_versions
    where household_id = p_household_id and ingredient_id = p_ingredient_id
    for update;

    -- Read the receipt *after* acquiring the serialization lock. Two
    -- concurrent retries cannot each credit a separate revision.
    select * into receipt from public.ingredient_conversion_receipts
    where event_id = p_event_id;
    if found then
        if receipt.household_id is distinct from p_household_id
           or receipt.ingredient_id is distinct from p_ingredient_id
           or receipt.payload_hash is distinct from digest_value then
            raise exception 'reused conversion event with different contents';
        end if;
        return receipt.resulting_revision;
    end if;

    if current_revision is distinct from p_expected_revision then
        raise exception 'conversion profile conflict; refresh the household';
    end if;

    -- No user input becomes dynamic SQL. Reject malformed JSON and
    -- conflicting units instead of silently selecting one row.
    for profile in select value from jsonb_array_elements(p_profiles) loop
        if jsonb_typeof(profile) <> 'object' then
            raise exception 'invalid profile entry';
        end if;
        unit_code := profile->>'unit_code';
        if unit_code is null or unit_code !~ '^[A-Z][A-Z0-9_]{0,39}$'
           then raise exception 'invalid unit'; end if;
        if jsonb_typeof(profile->'base_amount_per_unit') <> 'number' then
            raise exception 'invalid conversion amount';
        end if;
        factor := (profile->>'base_amount_per_unit')::numeric;
        if factor <= 0 or factor > 1000000000 then
            raise exception 'invalid conversion factor';
        end if;
        if jsonb_typeof(profile->'approximate') is distinct from 'boolean' then
            raise exception 'approximation flag required';
        end if;
        approximate := (profile->>'approximate')::boolean;
        provenance := coalesce(profile->>'source','household-confirmed');
        if length(provenance) not between 1 and 120 then
            raise exception 'invalid conversion source';
        end if;
    end loop;


    -- Measurement corrections update every recipe that actually remembers
    -- this specific household-defined unit and prior conversion. Changing
    -- retail PACK/CAN sizes never rewrites previously saved requirements.
    -- Old recipes without snapshots stay unchanged (their entered unit
    -- cannot be reconstructed safely).
    for profile in select value from jsonb_array_elements(p_profiles) loop
        unit_code := profile->>'unit_code';
        if unit_code not in ('PACK','CAN') then
            select c.base_amount_per_unit into previous_factor
            from public.ingredient_unit_conversions c
            where c.household_id = p_household_id
              and c.ingredient_id = p_ingredient_id
              and c.unit_code = profile->>'unit_code';
            if found then
                factor := (profile->>'base_amount_per_unit')::numeric;
                approximate := (profile->>'approximate')::boolean;
                if previous_factor is distinct from factor then
                    if exists (
                        select 1 from public.recipe_ingredients r
                        where r.household_id = p_household_id
                          and r.ingredient_id = p_ingredient_id
                          and r.display_unit_code = unit_code
                          and r.display_amount is not null
                          and r.base_per_display_unit is not null
                          and abs(r.base_per_display_unit -
                              previous_factor::double precision) <=
                              greatest(0.000001,abs(previous_factor::double precision)*1e-8)
                          and abs(r.amount_base::double precision -
                              r.display_amount*r.base_per_display_unit) <=
                              greatest(0.000001,abs(r.amount_base::double precision)*1e-8)
                          and r.display_amount * factor::double precision > 10000000000
                    ) then
                        raise exception 'corrected recipe amount exceeds safe maximum';
                    end if;
                    update public.recipe_ingredients r
                    set amount_base = r.display_amount * factor::double precision,
                        base_per_display_unit = factor::double precision,
                        conversion_approximate = approximate
                    where r.household_id = p_household_id
                      and r.ingredient_id = p_ingredient_id
                      and r.display_unit_code = unit_code
                      and r.display_amount is not null
                      and r.base_per_display_unit is not null
                      and abs(r.base_per_display_unit -
                          previous_factor::double precision) <=
                          greatest(0.000001,abs(previous_factor::double precision)*1e-8)
                      and abs(r.amount_base::double precision -
                          r.display_amount*r.base_per_display_unit) <=
                          greatest(0.000001,abs(r.amount_base::double precision)*1e-8);
                end if;
            end if;
        end if;
    end loop;

    delete from public.ingredient_unit_conversions
    where household_id=p_household_id and ingredient_id=p_ingredient_id;
    for profile in select value from jsonb_array_elements(p_profiles) loop
        insert into public.ingredient_unit_conversions(
            household_id,ingredient_id,unit_code,base_amount_per_unit,
            approximate,source,revision,updated_at
        ) values(
            p_household_id,p_ingredient_id,profile->>'unit_code',
            (profile->>'base_amount_per_unit')::numeric,
            (profile->>'approximate')::boolean,
            coalesce(profile->>'source','household-confirmed'),
            p_expected_revision + 1,now()
        );
    end loop;
    update public.ingredient_conversion_versions
        set revision = p_expected_revision + 1
    where household_id=p_household_id and ingredient_id=p_ingredient_id;
    insert into public.ingredient_conversion_receipts(
        event_id,household_id,ingredient_id,payload_hash,resulting_revision
    ) values (
        p_event_id,p_household_id,p_ingredient_id,digest_value,
        p_expected_revision + 1
    );
    return p_expected_revision + 1;
end;
$body$;
revoke all on function public.save_ingredient_conversions_once(uuid,uuid,bigint,jsonb,uuid)
    from public, anon;
grant execute on function public.save_ingredient_conversions_once(uuid,uuid,bigint,jsonb,uuid)
    to authenticated;
