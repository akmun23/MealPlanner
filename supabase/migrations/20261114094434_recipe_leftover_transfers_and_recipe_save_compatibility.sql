-- Database changes applied to MealPlanner on 2026-11-14.
-- Recipe hand-offs are household-scoped and quantities use base units.
create table public.recipe_leftover_transfers (
    id uuid primary key default gen_random_uuid(),
    household_id uuid not null references public.households(id) on delete cascade,
    source_recipe_id uuid not null,
    target_recipe_id uuid not null,
    ingredient_id uuid not null,
    amount_base numeric not null
        check (amount_base > 0 and amount_base <= 10000000000),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint recipe_leftover_transfers_distinct_recipes
        check (source_recipe_id <> target_recipe_id),
    constraint recipe_leftover_transfers_source_recipe_fkey
        foreign key (source_recipe_id, household_id)
        references public.recipes(id, household_id) on delete cascade,
    constraint recipe_leftover_transfers_target_recipe_fkey
        foreign key (target_recipe_id, household_id)
        references public.recipes(id, household_id) on delete cascade,
    constraint recipe_leftover_transfers_ingredient_fkey
        foreign key (ingredient_id, household_id)
        references public.ingredients(id, household_id) on delete restrict,
    constraint recipe_leftover_transfers_unique
        unique (household_id, source_recipe_id, target_recipe_id, ingredient_id)
);

create index recipe_leftover_transfers_source_idx
    on public.recipe_leftover_transfers (household_id, source_recipe_id);
create index recipe_leftover_transfers_target_idx
    on public.recipe_leftover_transfers (household_id, target_recipe_id);

create or replace function private.validate_recipe_leftover_transfer()
returns trigger
language plpgsql
set search_path to ''
as $function$
begin
    if not exists (
        select 1 from public.recipe_ingredients ri
        where ri.household_id = new.household_id
          and ri.recipe_id = new.source_recipe_id
          and ri.ingredient_id = new.ingredient_id
    ) then
        raise exception 'source recipe does not use this ingredient';
    end if;

    if not exists (
        select 1 from public.recipe_ingredients ri
        where ri.household_id = new.household_id
          and ri.recipe_id = new.target_recipe_id
          and ri.ingredient_id = new.ingredient_id
    ) then
        raise exception 'target recipe does not use this ingredient';
    end if;

    if exists (
        with recursive downstream(recipe_id) as (
            select new.target_recipe_id
            union
            select transfer.target_recipe_id
            from public.recipe_leftover_transfers transfer
            join downstream d on d.recipe_id = transfer.source_recipe_id
            where transfer.household_id = new.household_id
              and transfer.id is distinct from new.id
        )
        select 1 from downstream where recipe_id = new.source_recipe_id
    ) then
        raise exception 'leftover transfer would create a recipe cycle';
    end if;

    return new;
end;
$function$;

create trigger recipe_leftover_transfers_validate
before insert or update on public.recipe_leftover_transfers
for each row execute function private.validate_recipe_leftover_transfer();

create trigger recipe_leftover_transfers_set_updated_at
before update on public.recipe_leftover_transfers
for each row execute function private.set_updated_at();

alter table public.recipe_leftover_transfers enable row level security;

create policy "recipe_leftover_transfers_member_all"
on public.recipe_leftover_transfers
for all to authenticated
using (private.is_household_member(household_id))
with check (private.is_household_member(household_id));

-- Old Android clients may omit part of the optional display snapshot. Treat
-- an incomplete unit snapshot as absent while preserving the recipe quantity.
create or replace function private.normalize_recipe_estimate_write()
returns trigger
language plpgsql
set search_path to ''
as $function$
begin
    if new.display_amount is null
       or new.display_unit_code is null
       or new.base_per_display_unit is null then
        new.display_amount := null;
        new.display_unit_code := null;
        new.base_per_display_unit := null;
    end if;
    new.conversion_approximate := coalesce(new.conversion_approximate, false);
    return new;
end;
$function$;
