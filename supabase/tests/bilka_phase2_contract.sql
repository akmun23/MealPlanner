-- Execute only after the phase-2 SQL migration in a test/staging project.
-- No Prej keys or Vault secret values are required.
begin;
do $$
declare f text;
begin
  foreach f in array array[
    'public.prej_store_key(uuid,text)',
    'public.prej_read_key(uuid)',
    'public.prej_forget_key(uuid)',
    'public.prej_record_key_check(uuid,integer)',
    'public.prej_reserve_call(uuid,integer)',
    'public.prej_complete_call(uuid,integer)'
  ] loop
    if has_function_privilege('anon', f, 'EXECUTE')
       or has_function_privilege('authenticated', f, 'EXECUTE')
       or not has_function_privilege('service_role', f, 'EXECUTE') then
       raise exception 'Unsafe Prej RPC permissions: %', f;
    end if;
  end loop;
  if not exists (
    select 1 from pg_constraint
     where conrelid = 'public.prej_api_usage'::regclass
       and contype = 'f' and confrelid = 'auth.users'::regclass
  ) then
    raise exception 'Quota must survive credential removal, FK must reference auth.users';
  end if;
  if has_table_privilege('authenticated', 'public.prej_user_credentials', 'SELECT')
    or has_table_privilege('authenticated', 'public.prej_api_usage', 'SELECT')
  then
    raise exception 'Credentials/quota metadata must remain server-only';
  end if;
  raise notice 'Prej phase 2 RPC privileges and per-user budget: OK';
end $$;
rollback;
