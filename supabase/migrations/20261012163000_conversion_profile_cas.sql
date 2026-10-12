-- Prevent a stale phone's ingredient metadata write from erasing newer
-- household conversion factors. Profile sets are changed atomically with
-- compare-and-swap revisions and idempotent outbox event IDs.
create table if not exists public.ingredient_conversion_versions (
    household_id uuid not null,
    ingredient_id uuid not null,
    revision bigint not null default 0 check (revision >= 0),
    primary key(household_id, ingredient_id),
    foreign key(ingredient_id, household_id)
        references public.ingredients(id, household_id) on delete cascade
);
alter table public.ingredient_conversion_versions enable row level security;
revoke all on public.ingredient_conversion_versions from public, anon, authenticated;
grant select on public.ingredient_conversion_versions to authenticated;
create policy conversion_versions_read on public.ingredient_conversion_versions
    for select to authenticated
    using (private.is_household_member(household_id));

-- Profiles can only be changed through the household-scoped, versioned RPC.
revoke insert, update, delete on public.ingredient_unit_conversions from authenticated;

create table if not exists public.ingredient_conversion_receipts (
    event_id uuid primary key,
    household_id uuid not null,
    ingredient_id uuid not null,
    payload_hash text not null,
    resulting_revision bigint not null check (resulting_revision > 0)
);
alter table public.ingredient_conversion_receipts enable row level security;
revoke all on public.ingredient_conversion_receipts from public, anon, authenticated;

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
revoke all on function public.save_ingredient_conversions_once(
    uuid,uuid,bigint,jsonb,uuid
) from public, anon;
grant execute on function public.save_ingredient_conversions_once(
    uuid,uuid,bigint,jsonb,uuid
) to authenticated;
