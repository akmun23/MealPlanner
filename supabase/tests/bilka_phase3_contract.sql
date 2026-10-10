-- Bilka phase 3 contract: after migration, verify cache is server-owned.
begin;
do $$
begin
  if not exists (
    select 1
      from pg_class c
      join pg_namespace n on n.oid = c.relnamespace
     where n.nspname = 'public' and c.relname = 'bilka_search_cache'
       and c.relrowsecurity
  ) then
    raise exception 'bilka_search_cache missing or RLS disabled';
  end if;

  if has_table_privilege('anon', 'public.bilka_search_cache', 'SELECT')
      or has_table_privilege('authenticated', 'public.bilka_search_cache', 'SELECT')
      or has_table_privilege('authenticated', 'public.bilka_search_cache', 'INSERT')
      or has_table_privilege('authenticated', 'public.bilka_search_cache', 'UPDATE')
      or has_table_privilege('authenticated', 'public.bilka_search_cache', 'DELETE')
  then
    raise exception 'Search cache must be server-only';
  end if;

  if not has_table_privilege('service_role', 'public.bilka_search_cache', 'SELECT')
      or not has_table_privilege('service_role', 'public.bilka_search_cache', 'INSERT')
  then
    raise exception 'Edge service role cannot manage search cache';
  end if;

  raise notice 'Bilka phase 3 cache RLS and server grants: OK';
end $$;
rollback;
