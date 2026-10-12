-- A shared household's dinner can use several ingredients. Consumption
-- and meal_history must commit together; an outbox retry cannot spend again.
create table if not exists public.pantry_meal_completion_receipts (
    household_id uuid not null,
    eaten_on date not null,
    recipe_id uuid not null,
    event_id uuid not null,
    deductions_hash text not null,
    created_at timestamptz not null default now(),
    primary key(household_id, eaten_on),
    unique(event_id)
);
alter table public.pantry_meal_completion_receipts enable row level security;
revoke all on public.pantry_meal_completion_receipts from public, anon, authenticated;

create or replace function public.complete_meal_with_lots_once(
    p_household_id uuid,
    p_eaten_on date,
    p_recipe_id uuid,
    p_deductions jsonb,
    p_event_id uuid
) returns boolean language plpgsql security definer set search_path = ''
as $body$
declare
    previous public.pantry_meal_completion_receipts%rowtype;
    event_digest text;
    ingredient_key text;
    spent jsonb;
    amount numeric;
begin
    if not private.is_household_member(p_household_id) then
        raise exception 'not a household member';
    end if;
    if p_event_id is null or p_eaten_on is null or p_recipe_id is null
       or p_deductions is null or jsonb_typeof(p_deductions) <> 'object'
       or jsonb_object_length(p_deductions) > 200 then
        raise exception 'invalid meal completion request';
    end if;
    if p_eaten_on > current_date or p_eaten_on < current_date - 89 then
        raise exception 'meal date outside permitted history';
    end if;
    if not exists (
        select 1 from public.recipes
        where id=p_recipe_id and household_id=p_household_id
    ) then raise exception 'recipe does not belong to household'; end if;

    event_digest := md5(jsonb_build_object(
        'household', p_household_id, 'date',p_eaten_on,
        'recipe',p_recipe_id,'deductions',p_deductions
    )::text);

    insert into public.pantry_meal_completion_receipts(
        household_id,eaten_on,recipe_id,event_id,deductions_hash
    ) values(
        p_household_id,p_eaten_on,p_recipe_id,p_event_id,event_digest
    ) on conflict do nothing;

    if not found then
        select * into previous from public.pantry_meal_completion_receipts
        where household_id=p_household_id and eaten_on=p_eaten_on;
        if not found or previous.event_id is distinct from p_event_id
           or previous.recipe_id is distinct from p_recipe_id
           or previous.deductions_hash is distinct from event_digest then
            raise exception 'this meal has already been completed differently';
        end if;
        return true;
    end if;

    for ingredient_key, spent in select key, value
        from jsonb_each(p_deductions) order by key
    loop
        if jsonb_typeof(spent) <> 'number' then
            raise exception 'invalid ingredient consumption';
        end if;
        amount := (spent #>> '{}')::numeric;
        if amount <= 0 or amount > 10000000000 then
            raise exception 'invalid ingredient amount';
        end if;
        if not exists (
            select 1 from public.ingredients
            where household_id=p_household_id
              and id=ingredient_key::uuid
        ) then raise exception 'ingredient outside household'; end if;
        -- Uses row locks, opened-first selection and raises on insufficiency.
        -- The entire receipt and all prior ingredient deductions roll back
        -- when any single ingredient fails.
        perform public.consume_pantry_lots(
            p_household_id, ingredient_key::uuid, amount
        );
    end loop;

    insert into public.meal_history(
        household_id,eaten_on,recipe_id
    ) values (
        p_household_id,p_eaten_on,p_recipe_id
    ) on conflict (household_id,eaten_on) do nothing;
    if not found then raise exception 'meal history already recorded'; end if;
    return true;
end;
$body$;
revoke all on function public.complete_meal_with_lots_once(
    uuid,date,uuid,jsonb,uuid
) from public, anon;
grant execute on function public.complete_meal_with_lots_once(
    uuid,date,uuid,jsonb,uuid
) to authenticated;
