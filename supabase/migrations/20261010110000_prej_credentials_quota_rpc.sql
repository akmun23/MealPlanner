-- Bilka phase 2: encrypted user-provided Prej credentials + atomic per-user budgets.
-- Service-only RPCs are intentionally restricted to service_role; never call from Android.
-- Previous phase 1 schema must be applied before this migration.

-- Removing a Prej key must not reset its daily quota accounting.
alter table public.prej_api_usage
    drop constraint if exists prej_api_usage_user_id_fkey;
alter table public.prej_api_usage
    add constraint prej_api_usage_user_id_fkey
    foreign key (user_id) references auth.users(id) on delete cascade;

create or replace function public.prej_store_key(
    p_user_id uuid,
    p_api_key text
) returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_secret_id uuid;
begin
    if p_user_id is null
       or p_api_key is null
       or length(p_api_key) < 12
       or length(p_api_key) > 300
       or left(p_api_key, 5) <> 'prej_' then
        raise exception 'Invalid Prej credential';
    end if;

    select vault_secret_id
      into v_secret_id
      from public.prej_user_credentials
     where user_id = p_user_id
     for update;

    if v_secret_id is null then
        v_secret_id := vault.create_secret(
            p_api_key,
            'mealplanner-prej-' || p_user_id::text,
            'User-owned Prej key for MealPlanner'
        );
    else
        perform vault.update_secret(v_secret_id, p_api_key);
    end if;

    insert into public.prej_user_credentials(
        user_id, vault_secret_id, key_last_four, enabled, verified_at, updated_at
    ) values (
        p_user_id, v_secret_id, right(p_api_key, 4), true, now(), now()
    ) on conflict (user_id) do update set
        vault_secret_id = excluded.vault_secret_id,
        key_last_four = excluded.key_last_four,
        enabled = true,
        verified_at = now(),
        updated_at = now();
end
$$;

create or replace function public.prej_read_key(
    p_user_id uuid
) returns text
language sql
stable
security definer
set search_path = ''
as $$
    select v.decrypted_secret
      from public.prej_user_credentials c
      join vault.decrypted_secrets v on v.id = c.vault_secret_id
     where c.user_id = p_user_id
       and c.enabled = true
     limit 1;
$$;

create or replace function public.prej_forget_key(
    p_user_id uuid
) returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_secret_id uuid;
begin
    select vault_secret_id
      into v_secret_id
      from public.prej_user_credentials
     where user_id = p_user_id
     for update;
    if v_secret_id is not null then
        delete from public.prej_user_credentials where user_id = p_user_id;
        delete from vault.secrets where id = v_secret_id;
    end if;
    -- Intentionally retain today's prej_api_usage for this user.
end
$$;

create or replace function public.prej_record_key_check(
    p_user_id uuid,
    p_remaining integer
) returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
    insert into public.prej_api_usage(
        user_id, quota_date_utc, calls_reserved,
        calls_completed, last_remaining_reported
    ) values (
        p_user_id, (now() at time zone 'utc')::date,
        1, 1, greatest(0, p_remaining)
    ) on conflict (user_id, quota_date_utc) do update set
        calls_reserved = public.prej_api_usage.calls_reserved + 1,
        calls_completed = public.prej_api_usage.calls_completed + 1,
        last_remaining_reported = case
            when p_remaining is null
                then public.prej_api_usage.last_remaining_reported
            else least(
                coalesce(public.prej_api_usage.last_remaining_reported, p_remaining),
                greatest(0, p_remaining)
            )
        end,
        updated_at = now();
end
$$;

create or replace function public.prej_reserve_call(
    p_user_id uuid,
    p_daily_budget integer default 20
) returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_reserved integer;
begin
    if p_user_id is null or p_daily_budget < 1 or p_daily_budget > 20 then
        return false;
    end if;
    -- One atomic upsert guards concurrent Edge Function invocations.
    insert into public.prej_api_usage(user_id, quota_date_utc, calls_reserved)
    values (p_user_id, (now() at time zone 'utc')::date, 1)
    on conflict (user_id, quota_date_utc) do update set
        calls_reserved = public.prej_api_usage.calls_reserved + 1,
        updated_at = now()
    where public.prej_api_usage.calls_reserved < p_daily_budget
      and (public.prej_api_usage.last_remaining_reported is null
           or public.prej_api_usage.last_remaining_reported > 0)
    returning calls_reserved into v_reserved;
    return v_reserved is not null;
end
$$;

create or replace function public.prej_complete_call(
    p_user_id uuid,
    p_remaining integer default null
) returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
    update public.prej_api_usage
       set calls_completed = least(calls_reserved, calls_completed + 1),
           last_remaining_reported = case
               when p_remaining is null then last_remaining_reported
               else least(coalesce(last_remaining_reported, p_remaining), greatest(0,p_remaining))
           end,
           updated_at = now()
     where user_id = p_user_id
       and quota_date_utc = (now() at time zone 'utc')::date;
end
$$;

-- Explicitly revoke the default PUBLIC execute grant from SECURITY DEFINER RPCs.
revoke all on function public.prej_store_key(uuid,text) from public, anon, authenticated;
revoke all on function public.prej_read_key(uuid) from public, anon, authenticated;
revoke all on function public.prej_forget_key(uuid) from public, anon, authenticated;
revoke all on function public.prej_record_key_check(uuid,integer) from public, anon, authenticated;
revoke all on function public.prej_reserve_call(uuid,integer) from public, anon, authenticated;
revoke all on function public.prej_complete_call(uuid,integer) from public, anon, authenticated;
grant execute on function public.prej_store_key(uuid,text) to service_role;
grant execute on function public.prej_read_key(uuid) to service_role;
grant execute on function public.prej_forget_key(uuid) to service_role;
grant execute on function public.prej_record_key_check(uuid,integer) to service_role;
grant execute on function public.prej_reserve_call(uuid,integer) to service_role;
grant execute on function public.prej_complete_call(uuid,integer) to service_role;

comment on function public.prej_read_key(uuid) is
    'Returns decrypted user-owned Prej key ONLY to service_role. Do not expose or log result.';
