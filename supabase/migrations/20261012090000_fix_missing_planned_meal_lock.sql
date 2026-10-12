-- Compatibility repair for older Android builds that omit locked=false
-- when serializing planned_meals via Kotlinx Serialization/PostgREST.
-- The column is NOT NULL, and those existing app builds currently retry
-- their offline queue indefinitely after PostgreSQL error 23502.
--
-- DO NOT update existing rows: only normalize an incoming NULL to false.
-- Keep this trigger until active installed clients have the fixed serializer.
create or replace function private.default_missing_planned_meal_lock()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
    if new.locked is null then
        new.locked := false;
    end if;
    return new;
end;
$$;

drop trigger if exists planned_meals_default_missing_lock
    on public.planned_meals;

create trigger planned_meals_default_missing_lock
before insert or update on public.planned_meals
for each row
execute function private.default_missing_planned_meal_lock();

comment on trigger planned_meals_default_missing_lock
    on public.planned_meals is
    'Temporary compatibility: treat missing/NULL locked as false for older client upserts';
