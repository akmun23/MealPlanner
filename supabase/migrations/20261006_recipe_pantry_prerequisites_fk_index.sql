create index if not exists idx_recipe_pantry_prerequisites_recipe_household
    on public.recipe_pantry_prerequisites(recipe_id, household_id);
