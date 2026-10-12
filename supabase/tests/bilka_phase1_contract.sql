-- Run AFTER applying the phase-1 migration in a disposable/test Supabase database.
-- This intentionally needs no pgTAP extension. It makes no persistent data changes.
begin;

do $$
declare
    table_name text;
    expected_tables text[] := array[
        'bilka_products',
        'ingredient_bilka_candidates',
        'ingredient_bilka_defaults',
        'shopping_product_overrides',
        'bilka_discovery_jobs',
        'prej_user_credentials',
        'prej_api_usage'
    ];
begin
    foreach table_name in array expected_tables loop
        if not exists (
            select 1 from pg_class c
            join pg_namespace n on n.oid = c.relnamespace
            where n.nspname = 'public' and c.relname = table_name
              and c.relkind in ('r', 'p') and c.relrowsecurity
        ) then
            raise exception 'Missing table or RLS not enabled: %', table_name;
        end if;

        if has_table_privilege('anon', format('public.%I', table_name), 'SELECT')
            or has_table_privilege('anon', format('public.%I', table_name), 'INSERT')
            or has_table_privilege('anon', format('public.%I', table_name), 'UPDATE')
            or has_table_privilege('anon', format('public.%I', table_name), 'DELETE')
        then
            raise exception 'Anonymous access unexpectedly granted to %', table_name;
        end if;
    end loop;

    foreach table_name in array array[
        'bilka_products', 'ingredient_bilka_candidates', 'bilka_discovery_jobs'
    ] loop
        if not has_table_privilege('authenticated', format('public.%I', table_name), 'SELECT')
            or has_table_privilege('authenticated', format('public.%I', table_name), 'INSERT')
            or has_table_privilege('authenticated', format('public.%I', table_name), 'UPDATE')
            or has_table_privilege('authenticated', format('public.%I', table_name), 'DELETE')
        then
            raise exception 'Expected client read-only access to %', table_name;
        end if;
    end loop;

    foreach table_name in array array[
        'ingredient_bilka_defaults', 'shopping_product_overrides'
    ] loop
        if not has_table_privilege('authenticated', format('public.%I', table_name), 'SELECT')
            or not has_table_privilege('authenticated', format('public.%I', table_name), 'INSERT')
            or not has_table_privilege('authenticated', format('public.%I', table_name), 'UPDATE')
            or not has_table_privilege('authenticated', format('public.%I', table_name), 'DELETE')
        then
            raise exception 'Expected household write grants on %', table_name;
        end if;
    end loop;

    if has_table_privilege('authenticated', 'public.prej_user_credentials', 'SELECT')
        or has_table_privilege('authenticated', 'public.prej_user_credentials', 'INSERT')
        or has_table_privilege('authenticated', 'public.prej_user_credentials', 'UPDATE')
        or has_table_privilege('authenticated', 'public.prej_user_credentials', 'DELETE')
    then
        raise exception 'User-owned Prej key references must be server-only';
    end if;

    if has_table_privilege('authenticated', 'public.prej_api_usage', 'SELECT')
        or has_table_privilege('authenticated', 'public.prej_api_usage', 'INSERT')
        or has_table_privilege('authenticated', 'public.prej_api_usage', 'UPDATE')
        or has_table_privilege('authenticated', 'public.prej_api_usage', 'DELETE')
    then
        raise exception 'Quota accounting must be server-only';
    end if;

    if not exists (
        select 1 from pg_constraint
        where conrelid = 'public.prej_api_usage'::regclass
          and contype = 'p'
          and pg_get_constraintdef(oid) like '%(user_id, quota_date_utc)%'
    ) then
        raise exception 'Prej daily API budget must be per-user, not global';
    end if;

    if not exists (
        select 1 from pg_constraint
        where conrelid = 'public.prej_user_credentials'::regclass
          and contype = 'p'
          and pg_get_constraintdef(oid) like '%(user_id)%'
    ) then
        raise exception 'Prej credential storage must be per-user';
    end if;

    foreach table_name in array array[
        'ingredient_bilka_candidates', 'ingredient_bilka_defaults',
        'shopping_product_overrides', 'bilka_discovery_jobs'
    ] loop
        if not exists (
            select 1 from pg_policies p
            where p.schemaname = 'public'
              and p.tablename = table_name
              and p.qual like '%is_household_member%'
        ) then
            raise exception 'Missing household RLS policy on %', table_name;
        end if;
    end loop;

    if not exists (
        select 1 from pg_constraint c
        where c.conrelid = 'public.shopping_product_overrides'::regclass
          and c.contype = 'f'
          and pg_get_constraintdef(c.oid)
              like '%(weekly_plan_id, household_id)%'
    ) then
        raise exception 'Overrides must reference weekly plans with their household';
    end if;

    if not exists (
        select 1 from pg_constraint c
        where c.conrelid = 'public.ingredient_bilka_defaults'::regclass
          and c.contype = 'f'
          and pg_get_constraintdef(c.oid)
              like '%(ingredient_id, household_id)%'
    ) then
        raise exception 'Defaults must reference ingredients with their household';
    end if;

    raise notice 'Bilka phase-1 schema/grants/policies/foreign keys: OK';
end $$;

rollback;
