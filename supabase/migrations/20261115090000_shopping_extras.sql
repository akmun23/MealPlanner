-- Extra amounts on the weekly shopping list: "milk is in a recipe, but I also
-- need milk for coffee". One row per (week, ingredient); the app adds the
-- amount to what the week's recipes need. A separate table (not a column on
-- shopping_inclusions) because clearing/regenerating a week deletes inclusions,
-- while an extra is independent of the dinner plan. Older app versions never
-- touch this table. Apply this BEFORE releasing the app version that uses it.
create table if not exists public.shopping_extras (
    household_id uuid not null,
    weekly_plan_id uuid not null,
    ingredient_id uuid not null,
    extra_amount_base numeric not null
        check (extra_amount_base > 0 and extra_amount_base <= 1000000000),
    primary key (weekly_plan_id, ingredient_id),
    foreign key (weekly_plan_id, household_id)
        references public.weekly_plans(id, household_id)
        on delete cascade,
    foreign key (ingredient_id, household_id)
        references public.ingredients(id, household_id)
        on delete cascade
);

create index if not exists idx_shopping_extras_household
    on public.shopping_extras(household_id);

create index if not exists idx_shopping_extras_ingredient_household
    on public.shopping_extras(ingredient_id, household_id);

alter table public.shopping_extras enable row level security;

drop policy if exists shopping_extras_member_all
on public.shopping_extras;

create policy shopping_extras_member_all
on public.shopping_extras
for all
to authenticated
using (private.is_household_member(household_id))
with check (private.is_household_member(household_id));

grant select, insert, update, delete
on public.shopping_extras
to authenticated;

do $$
begin
    if not exists (
        select 1 from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'shopping_extras'
    ) then
        alter publication supabase_realtime
            add table public.shopping_extras;
    end if;
end
$$;
