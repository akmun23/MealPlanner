-- Correct PostgreSQL's invalid repetition count {12,256} from key staging.
-- PostgreSQL permits up to 255 as a regex repetition upper limit. Validate
-- the suffix length separately while retaining the original allowed charset.
-- This migration changes only server-side validation. No key or usage rows change.
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
    -- Prefix length is 9 for prej_dev_, prej_biz_, and prej_pro_.
    -- The provider token must contain 12..256 allowed characters.
    if p_user_id is null
       or p_api_key is null
       or length(p_api_key) not between 21 and 265
       or p_api_key !~ '^prej_(dev|biz|pro)_[A-Za-z0-9_-]+$' then
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

-- Preserve the strict RPC boundary even when a function is replaced.
revoke all on function public.prej_stage_key(uuid,text) from public, anon, authenticated;
grant execute on function public.prej_stage_key(uuid,text) to service_role;

comment on function public.prej_stage_key(uuid,text) is
    'Vault-backed pending Prej key; valid format checked without unsupported regex repetitions; service-role only.';
