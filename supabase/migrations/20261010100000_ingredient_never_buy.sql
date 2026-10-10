-- Keep a recipe ingredient visible while never adding it to automatic shopping.
-- Default false preserves the behaviour of every existing ingredient.
alter table public.ingredients
    add column if not exists never_buy boolean not null default false;

comment on column public.ingredients.never_buy is
    'Household preference: keep ingredient in recipes, but never generate a shopping item for it.';
