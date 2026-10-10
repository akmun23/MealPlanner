# Bilka phase 2 – server-only Prej integration

This phase implements a per-user BYO Prej key, atomic quotas, and batch refreshes for **already-selected Bilka products**. It does not search for new ingredient candidates (phase 3), schedule a nightly job (phase 5), or add the Android product selector (phase 4).

## Setup

1. Ensure phase-1 migration `bilka_phase1` has run.
2. Apply `supabase/migrations/20261010110000_prej_credentials_quota_rpc.sql`.
3. Execute `supabase/tests/bilka_phase2_contract.sql` in a test database. It verifies RPC privileges, quota foreign key and server-only tables. No real key is needed.
4. Deploy `supabase/functions/bilka-prej` as an Edge Function with **JWT verification enabled**.
5. Run `deno check supabase/functions/bilka-prej/index.ts` and `deno test supabase/functions/bilka-prej/prej_test.ts` (also checked by GitHub Actions).

The function uses Supabase's injected `SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY`. Do **not** put user keys or service keys in the repository, APK, app preferences, logs, or SQL migrations.

## Interface

All requests are authenticated POST requests to `/functions/v1/bilka-prej` with the signed-in user's Supabase JWT. The function verifies the user at Supabase Auth; the client **must not** pass a user ID.

- `{"action":"set-key","apiKey":"<user-owned Prej API key>"}`: validates the key once via Prej `/v1/stats`, encrypts via Vault, tracks that check against the user's daily quota. Error responses never echo secrets.
- `{"action":"status"}`: returns only connection status, last four characters, local budget/usage, and provider-reported remaining calls. No secret is returned.
- `{"action":"remove-key"}`: removes the Vault secret and credential reference, **preserving** today's per-user quota record.
- `{"action":"refresh","householdId":"<UUID>"}`: requires verified household membership, looks up this household's chosen product IDs, skips entries recently refreshed (within 20 hours), then queries **up to 500 IDs in one Prej batch call** with the caller's own key. Each result uses only the `prices[]` entry whose `chain_slug` is exactly `bilka`.

The Edge Function updates `bilka_products`, never `ingredient_bilka_defaults`. It stores product brands, optional product image URL, package quantity (preferred from the Bilka price row), package price in øre, comparison price, offer state and source `last_seen_date`.

## API budget

- Prej free account: 25 requests per UTC day, 120/minute burst. This service reserves **max 20 calls per user per UTC day** and retains 5 as a buffer. Key verification costs one call.
- `prej_reserve_call()` atomically reserves a call across concurrent Edge Functions; `prej_complete_call()` marks it spent even on network failure. Exhaustion returns HTTP 429, not a request to Prej.
- A replace/remove-and-add of a credential does not clear the current day's quota.
- Prej's returned `X-RateLimit-Remaining-Day` is stored without exposing the credential. Unexpectedly exhausted keys fail cleanly.
- **Do not pool or rotate different users' credentials** to work around a provider's account limits. Each user's quota is their own.
- Prej returns `scope=burst` or `scope=daily` on 429; these are reported separately.

## Security invariants

- All six security-sensitive functions are `SECURITY DEFINER` and granted EXECUTE to `service_role` **only**, with explicit `REVOKE ... FROM PUBLIC, anon, authenticated`. Audit grants during SQL review.
- Key material lives exclusively in Supabase Vault; `prej_user_credentials` stores an opaque Vault UUID and four trailing characters. No direct client table grant or RLS policy exists.
- Quotas persist on `auth.users` so removing a credential cannot reset the server-side daily counter.
- Product refresh checks `household_members` against the **verified** JWT user ID, preventing retrieval/update requests against another household.
- Cache writes do not alter fixed standard product choices or one-off shopping substitutions.
- Product photos are optional external URLs; confirm image use rights before display in client UI.

## Not yet supported

- Automatic bulk ingredient discovery / grouping (phase 3).
- User-facing Android key-entry and product-selection interfaces (phase 3/4).
- Nightly schedule (phase 5), which must respect the chosen account/credential.
- Multiple 500-ID batch windows per invocation; phase 2 handles one safe batch at a time.
- Deployment is a separate step from merging the GitHub PR and must occur after database migration/tests.
