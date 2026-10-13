-- PostgREST json_to_recordset inserts NULL when a Kotlin serialization
-- default-valued Boolean is omitted from a bulk upsert payload. SQL column
-- DEFAULT does not apply to explicit NULL, so older installed APKs can
-- repeatedly fail syncing recipe ingredients.
--
-- Normalize only this optional Boolean, before constraints and upsert conflict
-- resolution. Existing stored values and the recipe quantity are untouched.
create or replace function private.normalize_recipe_estimate_write()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $body$
begin
    new.conversion_approximate := coalesce(new.conversion_approximate, false);
    return new;
end;
$body$;

revoke all on function private.normalize_recipe_estimate_write()
    from public, anon, authenticated;

drop trigger if exists recipe_ingredients_normalize_estimate
    on public.recipe_ingredients;
create trigger recipe_ingredients_normalize_estimate
before insert or update of conversion_approximate
on public.recipe_ingredients
for each row execute function private.normalize_recipe_estimate_write();
