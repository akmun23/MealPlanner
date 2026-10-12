-- Allow a user to replace their Prej API key when today's local call budget
-- is exhausted. The replacement is encrypted but NOT trusted until Prej
-- verifies it on a later day. Quota reservations are intentionally untouched.
--
-- Apply this migration BEFORE deploying the associated bilka-prej Edge version.
-- Security: all three SECURITY DEFINER RPCs are service-role-only.

create or replace function public.prej_stage_key(
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
       or p_api_key !~ '^prej_(dev|biz|pro)_[a-zA-Z0-9_-]{12,256}$' then
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
        p_user_id, v_secret_id, right(p_api_key, 4), true, null, now()
    ) on conflict (user_id) do update set
        vault_secret_id = excluded.vault_secret_id,
        key_last_four = excluded.key_last_four,
        enabled = true,
        verified_at = null,
        updated_at = now();
end
$$;

-- Every action that uses the provider key (discovery, manual search, refresh)
-- reads only a key that has passed Prej verification.
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
       and c.verified_at is not null
     limit 1;
$$;

-- Verification alone may read a staged key that is not yet trusted.
create or replace function public.prej_read_pending_key(
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

revoke all on function public.prej_stage_key(uuid,text) from public, anon, authenticated;
revoke all on function public.prej_read_pending_key(uuid) from public, anon, authenticated;
revoke all on function public.prej_read_key(uuid) from public, anon, authenticated;
grant execute on function public.prej_stage_key(uuid,text) to service_role;
grant execute on function public.prej_read_pending_key(uuid) to service_role;
grant execute on function public.prej_read_key(uuid) to service_role;

comment on function public.prej_stage_key(uuid,text) is
    'Store unverified user key in Vault without an API call; usage budget is unchanged.';
comment on function public.prej_read_pending_key(uuid) is
    'Service-role-only read of a staged key exclusively for Prej verification.';
comment on function public.prej_read_key(uuid) is
    'Service-role-only read of an already verified user key for Prej operations.';
