-- Empty pantry packages should disappear from active state and cloud storage.
-- The existing lot synchronization trigger refreshes pantry_items after the
-- outer insert/update; its trigger name sorts after this prune trigger.
create or replace function private.prune_empty_pantry_lot()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $function$
begin
    if new.remaining_amount_base <= 0 then
        delete from public.pantry_lots
        where id = new.id
          and household_id = new.household_id
          and remaining_amount_base <= 0;
    end if;
    return new;
end;
$function$;

revoke all on function private.prune_empty_pantry_lot()
    from public, anon, authenticated;

drop trigger if exists pantry_lots_prune_empty
    on public.pantry_lots;
create trigger pantry_lots_prune_empty
after insert or update of remaining_amount_base
on public.pantry_lots
for each row execute function private.prune_empty_pantry_lot();

delete from public.pantry_lots
where remaining_amount_base = 0;
