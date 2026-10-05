-- Shared MealPlanner features added 2026-10-05.
-- Safe to re-run on the existing project.

alter table public.weekly_plans
    add column if not exists sandwich_day smallint
        check (sandwich_day between 1 and 7),
    add column if not exists sandwich_recipe_id uuid;

alter table public.planned_meals
    add column if not exists locked boolean not null default false;

alter table public.household_settings
    add column if not exists local_import_completed boolean not null default false;

alter table public.household_settings
    drop constraint if exists household_settings_sandwich_recipe_id_household_id_fkey;

alter table public.household_settings
    drop constraint if exists household_settings_sandwich_recipe_household_fkey;

alter table public.household_settings
    add constraint household_settings_sandwich_recipe_household_fkey
    foreign key (sandwich_recipe_id, household_id)
    references public.recipes(id, household_id)
    on delete set null (sandwich_recipe_id);

alter table public.planned_meals
    drop constraint if exists planned_meals_recipe_id_household_id_fkey;

alter table public.planned_meals
    drop constraint if exists planned_meals_recipe_household_fkey;

alter table public.planned_meals
    add constraint planned_meals_recipe_household_fkey
    foreign key (recipe_id, household_id)
    references public.recipes(id, household_id)
    on delete set null (recipe_id);

do $$
begin
    if not exists (
        select 1
        from pg_constraint
        where conname = 'weekly_plans_sandwich_recipe_household_fkey'
    ) then
        alter table public.weekly_plans
            add constraint weekly_plans_sandwich_recipe_household_fkey
            foreign key (sandwich_recipe_id, household_id)
            references public.recipes(id, household_id)
            on delete set null (sandwich_recipe_id);
    end if;
end
$$;

create index if not exists idx_weekly_plans_sandwich_recipe_household
    on public.weekly_plans(sandwich_recipe_id, household_id);

create table if not exists public.meal_history (
    id uuid primary key default gen_random_uuid(),
    household_id uuid not null references public.households(id) on delete cascade,
    eaten_on date not null,
    recipe_id uuid not null,
    source_weekly_plan_id uuid,
    created_at timestamptz not null default now(),
    unique (household_id, eaten_on),
    foreign key (recipe_id, household_id)
        references public.recipes(id, household_id)
        on delete cascade,
    foreign key (source_weekly_plan_id, household_id)
        references public.weekly_plans(id, household_id)
        on delete set null (source_weekly_plan_id)
);

create index if not exists idx_meal_history_household_eaten_on
    on public.meal_history(household_id, eaten_on desc);

create index if not exists idx_meal_history_recipe_household
    on public.meal_history(recipe_id, household_id);

create index if not exists idx_meal_history_source_plan_household
    on public.meal_history(source_weekly_plan_id, household_id);

alter table public.meal_history enable row level security;

drop policy if exists meal_history_member_all on public.meal_history;
create policy meal_history_member_all on public.meal_history
for all to authenticated
using (private.is_household_member(household_id))
with check (private.is_household_member(household_id));

grant select, insert, update, delete on public.meal_history to authenticated;

create table if not exists public.app_feedback (
    id uuid primary key default gen_random_uuid(),
    household_id uuid not null references public.households(id) on delete cascade,
    user_id uuid not null references auth.users(id) on delete cascade,
    message text not null check (length(trim(message)) between 3 and 2000),
    created_at timestamptz not null default now()
);

create index if not exists idx_app_feedback_household_created
    on public.app_feedback(household_id, created_at desc);

create index if not exists idx_app_feedback_user
    on public.app_feedback(user_id);

alter table public.app_feedback enable row level security;

drop policy if exists app_feedback_insert_member on public.app_feedback;
create policy app_feedback_insert_member
on public.app_feedback
for insert to authenticated
with check (
    user_id = (select auth.uid())
    and private.is_household_member(household_id)
);

drop policy if exists app_feedback_select_own on public.app_feedback;
create policy app_feedback_select_own
on public.app_feedback
for select to authenticated
using (
    user_id = (select auth.uid())
    and private.is_household_member(household_id)
);

grant insert, select on public.app_feedback to authenticated;

grant select, insert, update, delete on public.ingredients to authenticated;
grant select, insert, update, delete on public.recipes to authenticated;
grant select, insert, update, delete on public.recipe_ingredients to authenticated;
grant select, insert, update, delete on public.pantry_items to authenticated;
grant select, insert, update, delete on public.household_settings to authenticated;
grant select, insert, update, delete on public.weekly_plans to authenticated;
grant select, insert, update, delete on public.planned_meals to authenticated;
grant select, insert, update, delete on public.shopping_checks to authenticated;

do $$
begin
    if not exists (
        select 1
        from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'meal_history'
    ) then
        alter publication supabase_realtime add table public.meal_history;
    end if;
end
$$;


-- Structured meal types/categories and manually selected weekly lunches.
alter table public.recipes
    add column if not exists meal_type text not null default 'DINNER';

alter table public.recipes
    drop constraint if exists recipes_meal_type_check;

alter table public.recipes
    add constraint recipes_meal_type_check
    check (meal_type in ('DINNER', 'LUNCH'));

alter table public.recipes
    drop constraint if exists recipes_category_tag_check;

-- Category values are normalized in the Android app. Keep the database
-- permissive so older installed APKs can continue editing during rollout.

create table if not exists public.weekly_lunch_recipes (
    household_id uuid not null,
    weekly_plan_id uuid not null,
    recipe_id uuid not null,
    position smallint not null default 0,
    created_at timestamptz not null default now(),
    primary key (weekly_plan_id, recipe_id),
    foreign key (weekly_plan_id, household_id)
        references public.weekly_plans(id, household_id)
        on delete cascade,
    foreign key (recipe_id, household_id)
        references public.recipes(id, household_id)
        on delete cascade,
    check (position >= 0)
);

create index if not exists idx_weekly_lunch_recipes_household
    on public.weekly_lunch_recipes(household_id);

create index if not exists idx_weekly_lunch_recipes_recipe_household
    on public.weekly_lunch_recipes(recipe_id, household_id);

alter table public.weekly_lunch_recipes enable row level security;

drop policy if exists weekly_lunch_recipes_member_all
on public.weekly_lunch_recipes;

create policy weekly_lunch_recipes_member_all
on public.weekly_lunch_recipes
for all
to authenticated
using (private.is_household_member(household_id))
with check (private.is_household_member(household_id));

grant select, insert, update, delete
on public.weekly_lunch_recipes
to authenticated;

do $$
begin
    if not exists (
        select 1
        from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'weekly_lunch_recipes'
    ) then
        alter publication supabase_realtime
            add table public.weekly_lunch_recipes;
    end if;
end
$$;


create unique index if not exists uq_ingredients_household_normalized_name
    on public.ingredients (household_id, lower(btrim(name)));

create unique index if not exists uq_recipes_household_normalized_name
    on public.recipes (household_id, lower(btrim(name)));
