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

create index if not exists idx_weekly_lunch_recipes_plan_household
    on public.weekly_lunch_recipes(weekly_plan_id, household_id);

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


-- Shopping aisle/category metadata for grouped shopping lists.
alter table public.ingredients
    add column if not exists shopping_category text not null default 'OTHER';

alter table public.ingredients
    drop constraint if exists ingredients_shopping_category_check;

alter table public.ingredients
    add constraint ingredients_shopping_category_check
    check (
        shopping_category in (
            'PRODUCE',
            'MEAT_FISH',
            'DAIRY_CHILLED',
            'BREAD_BAKERY',
            'PANTRY',
            'SPICES',
            'FROZEN',
            'DRINKS',
            'OTHER'
        )
    );


-- Household-managed shopping categories. Keep the legacy shopping_category
-- text column during rollout so older APKs remain compatible.
create table if not exists public.shopping_categories (
    id uuid primary key default gen_random_uuid(),
    household_id uuid not null references public.households(id) on delete cascade,
    name text not null check (length(btrim(name)) between 1 and 60),
    position smallint not null default 0 check (position >= 0),
    created_at timestamptz not null default now(),
    unique (id, household_id)
);

create unique index if not exists uq_shopping_categories_household_name
    on public.shopping_categories (household_id, lower(btrim(name)));

alter table public.shopping_categories enable row level security;

drop policy if exists shopping_categories_member_all
on public.shopping_categories;

create policy shopping_categories_member_all
on public.shopping_categories
for all
to authenticated
using (private.is_household_member(household_id))
with check (private.is_household_member(household_id));

grant select, insert, update, delete
on public.shopping_categories
to authenticated;

alter table public.ingredients
    add column if not exists shopping_category_id uuid;

do $$
begin
    if not exists (
        select 1
        from pg_constraint
        where conname = 'ingredients_shopping_category_household_fkey'
    ) then
        alter table public.ingredients
            add constraint ingredients_shopping_category_household_fkey
            foreign key (shopping_category_id, household_id)
            references public.shopping_categories(id, household_id)
            on delete set null (shopping_category_id);
    end if;
end
$$;

create index if not exists idx_ingredients_shopping_category_household
    on public.ingredients(shopping_category_id, household_id);

insert into public.shopping_categories (household_id, name, position)
select
    h.id,
    defaults.name,
    defaults.position
from public.households h
cross join (
    values
        ('Frugt & grønt', 0),
        ('Kød & fisk', 1),
        ('Mejeri & køl', 2),
        ('Brød & bager', 3),
        ('Kolonial', 4),
        ('Krydderier', 5),
        ('Frost', 6),
        ('Drikkevarer', 7),
        ('Andet', 8)
) as defaults(name, position)
where not exists (
    select 1
    from public.shopping_categories existing
    where existing.household_id = h.id
      and lower(btrim(existing.name)) = lower(btrim(defaults.name))
);

update public.ingredients ingredient
set shopping_category_id = category.id
from public.shopping_categories category
where category.household_id = ingredient.household_id
  and ingredient.shopping_category_id is null
  and category.name = case ingredient.shopping_category
      when 'PRODUCE' then 'Frugt & grønt'
      when 'MEAT_FISH' then 'Kød & fisk'
      when 'DAIRY_CHILLED' then 'Mejeri & køl'
      when 'BREAD_BAKERY' then 'Brød & bager'
      when 'PANTRY' then 'Kolonial'
      when 'SPICES' then 'Krydderier'
      when 'FROZEN' then 'Frost'
      when 'DRINKS' then 'Drikkevarer'
      else 'Andet'
  end;

do $$
begin
    if not exists (
        select 1
        from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'shopping_categories'
    ) then
        alter publication supabase_realtime
            add table public.shopping_categories;
    end if;
end
$$;


-- Recipes can remain available for manual planning while being excluded
-- from automatic week generation and day rerolls. This lives in a side
-- table so older installed APKs can continue decoding public.recipes.
create table if not exists public.recipe_rotation_settings (
    household_id uuid not null,
    recipe_id uuid not null,
    auto_rotation_enabled boolean not null default true,
    primary key (household_id, recipe_id),
    foreign key (recipe_id, household_id)
        references public.recipes(id, household_id)
        on delete cascade
);

create index if not exists idx_recipe_rotation_recipe_household
    on public.recipe_rotation_settings(recipe_id, household_id);

alter table public.recipe_rotation_settings enable row level security;

drop policy if exists recipe_rotation_settings_member_all
on public.recipe_rotation_settings;

create policy recipe_rotation_settings_member_all
on public.recipe_rotation_settings
for all
to authenticated
using (private.is_household_member(household_id))
with check (private.is_household_member(household_id));

grant select, insert, update, delete
on public.recipe_rotation_settings
to authenticated;

do $
begin
    if not exists (
        select 1
        from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'recipe_rotation_settings'
    ) then
        alter publication supabase_realtime
            add table public.recipe_rotation_settings;
    end if;
end
$;


-- Recipe preparation time and per-day planning limits live in side tables so
-- older installed APKs can keep decoding recipes/planned_meals unchanged.
create table if not exists public.recipe_time_settings (
    household_id uuid not null,
    recipe_id uuid not null,
    prep_minutes smallint,
    primary key (household_id, recipe_id),
    foreign key (recipe_id, household_id)
        references public.recipes(id, household_id)
        on delete cascade,
    check (prep_minutes is null or prep_minutes between 1 and 1440)
);

create index if not exists idx_recipe_time_recipe_household
    on public.recipe_time_settings(recipe_id, household_id);

alter table public.recipe_time_settings enable row level security;

drop policy if exists recipe_time_settings_member_all
on public.recipe_time_settings;

create policy recipe_time_settings_member_all
on public.recipe_time_settings
for all
to authenticated
using (private.is_household_member(household_id))
with check (private.is_household_member(household_id));

grant select, insert, update, delete
on public.recipe_time_settings
to authenticated;

create table if not exists public.weekly_day_constraints (
    household_id uuid not null,
    weekly_plan_id uuid not null,
    day_of_week smallint not null check (day_of_week between 1 and 7),
    max_prep_minutes smallint not null check (max_prep_minutes between 1 and 1440),
    primary key (weekly_plan_id, day_of_week),
    foreign key (weekly_plan_id, household_id)
        references public.weekly_plans(id, household_id)
        on delete cascade
);

create index if not exists idx_weekly_day_constraints_household
    on public.weekly_day_constraints(household_id);

create index if not exists idx_weekly_day_constraints_plan_household
    on public.weekly_day_constraints(weekly_plan_id, household_id);

alter table public.weekly_day_constraints enable row level security;

drop policy if exists weekly_day_constraints_member_all
on public.weekly_day_constraints;

create policy weekly_day_constraints_member_all
on public.weekly_day_constraints
for all
to authenticated
using (private.is_household_member(household_id))
with check (private.is_household_member(household_id));

grant select, insert, update, delete
on public.weekly_day_constraints
to authenticated;

do $$
begin
    if not exists (
        select 1 from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'recipe_time_settings'
    ) then
        alter publication supabase_realtime
            add table public.recipe_time_settings;
    end if;

    if not exists (
        select 1 from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'weekly_day_constraints'
    ) then
        alter publication supabase_realtime
            add table public.weekly_day_constraints;
    end if;
end
$$;


-- Ingredient protein flags and household-managed recipe categories.
-- These live in side tables so older installed APKs can keep reading
-- ingredients/recipes without new columns appearing in their DTOs.

create table if not exists public.ingredient_protein_flags (
    household_id uuid not null,
    ingredient_id uuid not null,
    is_protein boolean not null default false,
    primary key (household_id, ingredient_id),
    foreign key (ingredient_id, household_id)
        references public.ingredients(id, household_id)
        on delete cascade
);

create index if not exists idx_ingredient_protein_ingredient_household
    on public.ingredient_protein_flags(ingredient_id, household_id);

alter table public.ingredient_protein_flags enable row level security;

drop policy if exists ingredient_protein_flags_member_all
on public.ingredient_protein_flags;

create policy ingredient_protein_flags_member_all
on public.ingredient_protein_flags
for all
to authenticated
using (private.is_household_member(household_id))
with check (private.is_household_member(household_id));

grant select, insert, update, delete
on public.ingredient_protein_flags
to authenticated;

create table if not exists public.recipe_categories (
    id uuid primary key default gen_random_uuid(),
    household_id uuid not null references public.households(id) on delete cascade,
    name text not null check (length(btrim(name)) between 1 and 60),
    position smallint not null default 0 check (position >= 0),
    created_at timestamptz not null default now(),
    unique (id, household_id)
);

create unique index if not exists uq_recipe_categories_household_name
    on public.recipe_categories(household_id, lower(btrim(name)));

alter table public.recipe_categories enable row level security;

drop policy if exists recipe_categories_member_all
on public.recipe_categories;

create policy recipe_categories_member_all
on public.recipe_categories
for all
to authenticated
using (private.is_household_member(household_id))
with check (private.is_household_member(household_id));

grant select, insert, update, delete
on public.recipe_categories
to authenticated;

create table if not exists public.recipe_category_assignments (
    household_id uuid not null,
    recipe_id uuid not null,
    category_id uuid not null,
    primary key (household_id, recipe_id),
    foreign key (recipe_id, household_id)
        references public.recipes(id, household_id)
        on delete cascade,
    foreign key (category_id, household_id)
        references public.recipe_categories(id, household_id)
        on delete restrict
);

create index if not exists idx_recipe_category_assignment_category_household
    on public.recipe_category_assignments(category_id, household_id);

alter table public.recipe_category_assignments enable row level security;

drop policy if exists recipe_category_assignments_member_all
on public.recipe_category_assignments;

create policy recipe_category_assignments_member_all
on public.recipe_category_assignments
for all
to authenticated
using (private.is_household_member(household_id))
with check (private.is_household_member(household_id));

grant select, insert, update, delete
on public.recipe_category_assignments
to authenticated;

insert into public.recipe_categories(household_id, name, position)
select
    h.id,
    defaults.name,
    defaults.position
from public.households h
cross join (
    values
        ('Pasta', 0),
        ('Karry', 1),
        ('Gryderet', 2),
        ('Ovnret', 3),
        ('Burger', 4),
        ('Tacos', 5),
        ('Sandwich/wrap', 6),
        ('Risret', 7),
        ('Suppe', 8),
        ('Salat/bowl', 9),
        ('Pizza/tærte', 10),
        ('Andet', 11)
) as defaults(name, position)
where not exists (
    select 1
    from public.recipe_categories existing
    where existing.household_id = h.id
      and lower(btrim(existing.name)) = lower(btrim(defaults.name))
);

insert into public.recipe_category_assignments(
    household_id,
    recipe_id,
    category_id
)
select
    recipe.household_id,
    recipe.id,
    category.id
from public.recipes recipe
join public.recipe_categories category
  on category.household_id = recipe.household_id
 and lower(category.name) = lower(
     case lower(btrim(recipe.category_tag))
         when 'pasta' then 'Pasta'
         when 'curry' then 'Karry'
         when 'karry' then 'Karry'
         when 'stew' then 'Gryderet'
         when 'gryderet' then 'Gryderet'
         when 'oven' then 'Ovnret'
         when 'ovnret' then 'Ovnret'
         when 'burger' then 'Burger'
         when 'tacos' then 'Tacos'
         when 'sandwich_wrap' then 'Sandwich/wrap'
         when 'sandwich' then 'Sandwich/wrap'
         when 'sandwich/wrap' then 'Sandwich/wrap'
         when 'wrap' then 'Sandwich/wrap'
         when 'rice' then 'Risret'
         when 'ris' then 'Risret'
         when 'risret' then 'Risret'
         when 'soup' then 'Suppe'
         when 'suppe' then 'Suppe'
         when 'salad_bowl' then 'Salat/bowl'
         when 'salat' then 'Salat/bowl'
         when 'salat/bowl' then 'Salat/bowl'
         when 'pizza_pie' then 'Pizza/tærte'
         when 'pizza' then 'Pizza/tærte'
         when 'pizza/tærte' then 'Pizza/tærte'
         else 'Andet'
     end
 )
on conflict (household_id, recipe_id) do nothing;

insert into public.ingredient_protein_flags(
    household_id,
    ingredient_id,
    is_protein
)
select
    household_id,
    id,
    false
from public.ingredients
on conflict (household_id, ingredient_id) do nothing;

do $$
begin
    if not exists (
        select 1 from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'ingredient_protein_flags'
    ) then
        alter publication supabase_realtime
            add table public.ingredient_protein_flags;
    end if;

    if not exists (
        select 1 from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'recipe_categories'
    ) then
        alter publication supabase_realtime
            add table public.recipe_categories;
    end if;

    if not exists (
        select 1 from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'recipe_category_assignments'
    ) then
        alter publication supabase_realtime
            add table public.recipe_category_assignments;
    end if;
end
$$;
