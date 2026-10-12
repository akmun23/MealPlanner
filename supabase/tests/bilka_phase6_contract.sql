-- Read-only role/grant contract for phase 6. Run only after deployment.
begin;
do $$
declare v_def text;
begin
  if has_function_privilege(
    'anon',
    'public.bilka_record_purchase(uuid,uuid,uuid,text,text,integer,numeric,bigint)',
    'EXECUTE'
  ) or has_function_privilege(
    'authenticated',
    'public.bilka_record_purchase(uuid,uuid,uuid,text,text,integer,numeric,bigint)',
    'EXECUTE'
  ) or not has_function_privilege(
    'service_role',
    'public.bilka_record_purchase(uuid,uuid,uuid,text,text,integer,numeric,bigint)',
    'EXECUTE'
  ) then
    raise exception 'bilka_record_purchase must be service-role only';
  end if;

  select pg_get_functiondef(
    'public.bilka_record_purchase(uuid,uuid,uuid,text,text,integer,numeric,bigint)'::regprocedure
  ) into v_def;

  if position('shopping_product_overrides' in v_def) = 0
    or position('pantry_items' in v_def) = 0
    or position('purchased_amount_base is null' in lower(v_def)) = 0 then
    raise exception 'Purchase RPC is missing weekly purchase or duplicate-protection logic';
  end if;
  if position('ingredient_bilka_defaults' in v_def) > 0 then
    raise exception 'Purchase function must never modify household default product';
  end if;
  raise notice 'Phase 6: one-time purchase RPC grants and structure OK';
end $$;
rollback;
