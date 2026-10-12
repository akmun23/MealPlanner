-- Execute only after phase-5 migration is applied in a test project.
-- All fixture writes are rolled back; no keys or quotas are consumed.
begin;
do $$
declare
    v_name text;
    v_updated integer;
begin
    if has_table_privilege('authenticated', 'public.prej_nightly_preferences', 'SELECT')
       or has_table_privilege('authenticated', 'public.bilka_nightly_runs', 'SELECT')
       or has_function_privilege('authenticated', 'public.bilka_update_prices_only(jsonb)', 'EXECUTE')
       or has_function_privilege('anon', 'public.bilka_update_prices_only(jsonb)', 'EXECUTE')
       or not has_function_privilege('service_role', 'public.bilka_update_prices_only(jsonb)', 'EXECUTE')
    then
        raise exception 'Nightly preferences, runs or price update RPC have unsafe grants';
    end if;

    insert into public.bilka_products (
        id, name, brand, chain_slug, package_quantity, package_unit,
        price_ore, image_url
    ) values (
        '9999999876500001', 'Original flour 2 kg', 'Original brand',
        'bilka', 2, 'kg', 1000, 'https://example.com/old-photo.jpg'
    );

    v_updated := public.bilka_update_prices_only(
        '[{"id":"9999999876500001","price_ore":2495,"unit_price_ore":1247.5,
           "unit_price_basis":"KG","is_offer":false,
           "price_updated_at":"2026-10-10T12:00:00Z"}]'::jsonb
    );
    if v_updated <> 1 then
        raise exception 'Batch update must affect one product';
    end if;

    select name into v_name from public.bilka_products where id='9999999876500001';
    if v_name <> 'Original flour 2 kg'
        or not exists (
            select 1 from public.bilka_products
            where id='9999999876500001'
              and brand='Original brand'
              and package_quantity=2
              and image_url='https://example.com/old-photo.jpg'
              and price_ore=2495
        )
    then
        raise exception 'Nightly pricing changed SKU identity or packaging';
    end if;
    raise notice 'Phase 5: server-only grants and SKU preservation OK';
end $$;
rollback;
