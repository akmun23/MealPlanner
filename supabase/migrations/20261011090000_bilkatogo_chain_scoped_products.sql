-- Keep historical Bilka prices intact while allowing a separate
-- BilkaToGo SKU/price for the same Prej product ID.
-- Legacy product IDs are numeric for bilka; the new togo IDs use a prefix.
-- Never rename/relabel an existing Bilka price row as BilkaToGo.
alter table public.bilka_products
  drop constraint if exists bilka_products_chain_slug_check;

alter table public.bilka_products
  add constraint bilka_products_chain_slug_check
  check (
    (chain_slug = 'bilka' and id ~ '^[0-9]+$') or
    (chain_slug = 'bilkatogo' and id ~ '^bilkatogo:[0-9]+$')
  );

comment on column public.bilka_products.id is
  'Chain-scoped product ID: legacy numeric Prej IDs for Bilka; bilkatogo:<Prej ID> for BilkaToGo.';

-- The existing price-only RPC only accepted numeric Bilka IDs.
-- Permit prefixed ToGo IDs, and check the retailer before updating. Keep
-- the data in its original row; never change SKU, brand, pack size or source.
create or replace function public.bilka_update_prices_only(p_updates jsonb)
returns integer
language plpgsql
security definer
set search_path = ''
as $function$
declare
  v_count integer;
begin
  if jsonb_typeof(p_updates) is distinct from 'array'
     or jsonb_array_length(p_updates) > 500 then
    raise exception 'Expected a JSON array with no more than 500 prices';
  end if;

  with incoming as (
    select distinct on (id)
      id, price_ore, unit_price_ore, unit_price_basis, is_offer, price_updated_at
    from jsonb_to_recordset(p_updates) as x(
      id text, price_ore bigint, unit_price_ore numeric,
      unit_price_basis text, is_offer boolean, price_updated_at timestamptz
    )
    where (id ~ '^[0-9]+$' or id ~ '^bilkatogo:[0-9]+$') and price_ore >= 0
    order by id
  ), applied as (
    update public.bilka_products p
    set price_ore = i.price_ore,
        unit_price_ore = i.unit_price_ore,
        unit_price_basis = i.unit_price_basis,
        is_offer = i.is_offer,
        price_updated_at = coalesce(i.price_updated_at, now()),
        updated_at = now()
    from incoming i
    where p.id = i.id
      and p.chain_slug = case
        when i.id like 'bilkatogo:%' then 'bilkatogo' else 'bilka'
      end
    returning p.id
  )
  select count(*) into v_count from applied;
  return v_count;
end
$function$;

-- Preserve service-role-only permissions of the previous definer function.
revoke all on function public.bilka_update_prices_only(jsonb)
  from public, anon, authenticated;
grant execute on function public.bilka_update_prices_only(jsonb) to service_role;
