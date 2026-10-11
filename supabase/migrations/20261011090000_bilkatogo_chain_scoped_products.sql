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
