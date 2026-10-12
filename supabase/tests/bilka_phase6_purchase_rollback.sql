-- Integration test against a real PostgreSQL transaction. All fixture data rolled back.
-- Run after phase-6 migration in a staging database, or wrap the phase-6 migration
-- before this script inside one explicit BEGIN/ROLLBACK transaction.
begin;
do $purchase_test$
declare
  h uuid := gen_random_uuid(); w uuid := gen_random_uuid();
  ingredient uuid := gen_random_uuid(); sku_ingredient uuid := gen_random_uuid();
  owner_id uuid; sku_id text := '999999987600001';
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
  -- Second ingredient: a chosen Bilka SKU is registered without changing
  -- the household default or requiring a real external Prej call.
  insert into public.ingredients(id,household_id,name,quantity_type,preferred_unit)
    values(sku_ingredient,h,'Temporary SKU flour','MASS','KILOGRAM');
  insert into public.bilka_products(
    id,chain_slug,name,brand,package_quantity,package_unit,
    package_base_amount,quantity_kind,price_ore
  ) values(sku_id,'bilka','Temporary flour 1kg','Testbrand',1,'kg',1000,'MASS',1250);
  insert into public.ingredient_bilka_defaults(household_id,ingredient_id,product_id)
    values(h,sku_ingredient,sku_id);
  added := public.bilka_record_purchase(
    h,w,sku_ingredient,sku_id,null,2,2000,2500
  );
  select amount_base into stored from public.pantry_items
    where household_id=h and ingredient_id=sku_ingredient;
  if added <> 2000 or stored <> 2000 then
    raise exception 'Incorrect SKU stock credit: %, %',added,stored;
  end if;
  if not exists(
    select 1 from public.ingredient_bilka_defaults
    where household_id=h and ingredient_id=sku_ingredient and product_id=sku_id
  ) then raise exception 'Checkout changed permanent product choice'; end if;
    raise notice 'Purchase fixture passed: 300 + 2000 = 2300, replay rejected';
end;
$purchase_test$;
rollback;