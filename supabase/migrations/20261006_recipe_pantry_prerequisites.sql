-- Optional stock prerequisites for automatic recipe rotation.
-- Older APKs ignore this side table, so the rollout remains backwards compatible.

create table if not exists public.recipe_pantry_prerequisites (
    household_id uuid not null,
    recipe_id uuid not null,
    ingredient_id uuid not null,
    minimum_amount_base double precision not null,
    created_at timestamptz not null default now(),
    primary key (recipe_id, ingredient_id),
    foreign key (household_id)
        references public.households(id)
        on delete cascade,
    foreign key (recipe_id, household_id)
        references public.recipes(id, household_id)
        on delete cascade,
    foreign key (ingredient_id, household_id)
        references public.ingredients(id, household_id)
        on delete restrict,
    check (minimum_amount_base > 0)
);

create index if not exists idx_recipe_pantry_prerequisites_household
    on public.recipe_pantry_prerequisites(household_id);

create index if not exists idx_recipe_pantry_prerequisites_ingredient_household
    on public.recipe_pantry_prerequisites(ingredient_id, household_id);

alter table public.recipe_pantry_prerequisites enable row level security;

drop policy if exists recipe_pantry_prerequisites_member_all
on public.recipe_pantry_prerequisites;

create policy recipe_pantry_prerequisites_member_all
on public.recipe_pantry_prerequisites
for all
to authenticated
using (private.is_household_member(household_id))
with check (private.is_household_member(household_id));

grant select, insert, update, delete
on public.recipe_pantry_prerequisites
to authenticated;

do $$
begin
    if not exists (
        select 1
        from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'recipe_pantry_prerequisites'
    ) then
        alter publication supabase_realtime
            add table public.recipe_pantry_prerequisites;
    end if;
end
$$;
