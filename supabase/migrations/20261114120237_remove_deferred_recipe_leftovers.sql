-- Remove the postponed tandem-leftover feature. No application code uses it,
-- and the table was verified empty before cleanup.
drop table if exists public.recipe_leftover_transfers;
drop function if exists private.validate_recipe_leftover_transfer();
