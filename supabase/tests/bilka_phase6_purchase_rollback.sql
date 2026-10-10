-- Integration test against a real PostgreSQL transaction. All fixture data rolled back.
-- Run after phase-6 migration in a staging database, or wrap the phase-6 migration
-- before this script inside one explicit BEGIN/ROLLBACK transaction.
begin;
do $purchase_test$
declare
  h uuid := gen_random_uuid(); w uuid := gen_random_uuid();
  ingredient uuid := gen_random_uuid(); owner_id uuid;
  added numeric; stored numeric; repeated boolean := false;
begin
  select id into owner_id from auth.users limit 1;
  if owner_id is null then
    raise notice 'No auth user: fixture cannot be created (skipping).';
    return;
  end if;
  insert into public.households(id,name,join_code,created_by)
  values(h,'Temporary checkout test',substring(md5(gen_random_uuid()::text) from 1 for 8),owner_id);
  insert into public.weekly_plans(id,household_id,week_start)
  values(w,h,'2040-01-02');
  insert into public.ingredients(id,household_id,name,quantity_type,preferred_unit)
  values(ingredient,h,'Temporary 1 kg flour','MASS','KILOGRAM');
  insert into public.pantry_items(household_id,ingredient_id,amount_base)
  values(h,ingredient,300);
  added := public.bilka_record_purchase(h,w,ingredient,null,'Temporary brand',2,2000,2495);
  select amount_base into stored from public.pantry_items
    where household_id=h and ingredient_id=ingredient;
  if added <> 2000 or stored <> 2300 then
    raise exception 'Incorrect whole-package credit: %, %',added,stored;
  end if;
  begin
    perform public.bilka_record_purchase(h,w,ingredient,null,'Temporary brand',2,2000,2495);
  exception when others then repeated := true;
  end;
  select amount_base into stored from public.pantry_items
    where household_id=h and ingredient_id=ingredient;
  if not repeated or stored <> 2300 then
    raise exception 'Double checkout was not blocked: %, %',repeated,stored;
  end if;
  if not exists (
    select 1 from public.shopping_product_overrides
    where household_id=h and weekly_plan_id=w and ingredient_id=ingredient
      and product_id is null and manual_product_name='Temporary brand'
      and package_count=2 and purchased_amount_base=2000 and paid_total_ore=2495
  ) then raise exception 'Purchase record is missing'; end if;
  raise notice 'Purchase fixture passed: 300 + 2000 = 2300, replay rejected';
end;
$purchase_test$;
rollback;