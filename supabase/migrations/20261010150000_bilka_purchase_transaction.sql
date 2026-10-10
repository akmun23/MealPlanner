-- Atomic shopping purchase: prevent duplicate stock additions.
create or replace function public.bilka_record_purchase(
 p_household_id uuid, p_weekly_plan_id uuid, p_ingredient_id uuid,
 p_product_id text, p_manual_product_name text, p_package_count integer,
 p_purchased_amount_base numeric, p_paid_total_ore bigint
) returns numeric language plpgsql security definer set search_path = ''
as $body$
declare v_added numeric;
begin
 if (p_product_id is null) = (p_manual_product_name is null) then raise exception 'product selection required'; end if;
 if p_purchased_amount_base is null or p_purchased_amount_base <= 0 or p_purchased_amount_base > 10000000 then raise exception 'invalid amount'; end if;
 if p_package_count is not null and (p_package_count < 1 or p_package_count > 500) then raise exception 'invalid packages'; end if;
 if p_product_id is not null and p_package_count is null then raise exception 'package count required'; end if;
 if p_paid_total_ore is not null and (p_paid_total_ore < 0 or p_paid_total_ore > 1000000000) then raise exception 'invalid price'; end if;
 if not exists(select 1 from public.weekly_plans where id=p_weekly_plan_id and household_id=p_household_id) then raise exception 'unknown week'; end if;
 if not exists(select 1 from public.ingredients where id=p_ingredient_id and household_id=p_household_id) then raise exception 'unknown ingredient'; end if;
 insert into public.shopping_product_overrides(household_id,weekly_plan_id,ingredient_id,product_id,manual_product_name,package_count,purchased_amount_base,paid_total_ore)
 values(p_household_id,p_weekly_plan_id,p_ingredient_id,p_product_id,p_manual_product_name,p_package_count,p_purchased_amount_base,p_paid_total_ore)
 on conflict(weekly_plan_id,ingredient_id) do update set
 product_id=excluded.product_id,manual_product_name=excluded.manual_product_name,
 package_count=excluded.package_count,purchased_amount_base=excluded.purchased_amount_base,
 paid_total_ore=excluded.paid_total_ore,updated_at=now()
 where public.shopping_product_overrides.purchased_amount_base is null
 returning purchased_amount_base into v_added;
 if v_added is null then raise exception 'already recorded'; end if;
 insert into public.pantry_items(household_id,ingredient_id,amount_base)
 values(p_household_id,p_ingredient_id,v_added)
 on conflict(household_id,ingredient_id) do update set
 amount_base=public.pantry_items.amount_base+excluded.amount_base,updated_at=now();
 return v_added;
end;
$body$;
revoke all on function public.bilka_record_purchase(uuid,uuid,uuid,text,text,integer,numeric,bigint) from public,anon,authenticated;
grant execute on function public.bilka_record_purchase(uuid,uuid,uuid,text,text,integer,numeric,bigint) to service_role;