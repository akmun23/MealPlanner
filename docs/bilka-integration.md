# Price lookup (Bilka via Prej)

The shopping list can show a store product, how many packs to buy and a rough price for the week.
The data comes from [Prej](https://prej.dk/api/dokumentation), a Danish grocery price API. Each
person brings their own Prej key, because the free tier is small. The app limits itself to 20 calls
per user per day.

The feature is optional. Without a key the app works as before, and shopping falls back to manual
entry.

## Why an Edge Function

The phone never talks to Prej. Everything goes through the `bilka-prej` Edge Function, for three
reasons:

- **Key handling.** The user types the key once. The function checks it against Prej and stores it
  in Supabase Vault. If the day's quota is already used up, it is staged unverified and checked
  later. The server only returns the last four characters and a status, and the app does not put the
  key in preferences, Room, logs or `BuildConfig`.
- **Budget.** Calls are reserved atomically in the database (`prej_reserve_call`, then
  `prej_complete_call`), so two requests at once cannot overspend the day. The app limit is 20 calls
  per user per day, which leaves a buffer under Prej's 25. Running out returns HTTP 429 without
  calling Prej.
- **Authorisation.** Every request carries the user's Supabase JWT. The function checks it, then
  checks household membership before touching household data. The client never sends a user id.

The service role is only used from the function, and the function may only call the database
functions listed in `rpc_allowlist.ts`. Those functions are `SECURITY DEFINER`, revoked from
`public`, `anon` and `authenticated`, and granted to `service_role` only.

## Actions

All are `POST /functions/v1/bilka-prej` with a JSON body `{ "action": …, … }`.

| Action | Purpose |
|---|---|
| `status`, `set-key`, `verify-key`, `remove-key` | Manage the user's Prej key and show the quota |
| `discover`, `discovery-status` | Bulk discovery, currently switched off. It answers `manual_search_recommended` |
| `bulk-search`, `manual-search`, `ean-lookup` | Search by name, or by barcode |
| `catalog`, `choose-product` | Read candidates and save the household's standard product |
| `refresh` | Update prices of the products already chosen (one batch call, up to 500 ids) |
| `shopping-context`, `purchase-status`, `record-purchase` | Pack counts for the week, and an idempotent purchase that credits the pantry |
| `nightly-status`, `set-nightly` | Opt in to the nightly price refresh |

## Decisions worth knowing about

- **The household picks the product.** A product id means one brand and one pack size. The app
  suggests candidates, but nothing is chosen or swapped for you when prices change. A one-off
  alternative applies only to that week's purchase.
- **Search results are only candidates.** Manual search shows what Prej returns, in the provider's
  order, including unrelated items, and a person confirms the pick. Results are cached for seven days
  in `bilka_search_cache`.
- **Barcode lookups check the right store.** A GTIN can be shared by variants and chains, so the
  price is only used when it comes from the matching chain's own price row (`chain_gtins`), not from
  the product's general barcode list. Positive lookups are cached for seven days, negative ones for
  one.
- **Prices stay separate from choices.** Refreshing writes only price fields on `bilka_products`.
  It never edits the household's chosen product, a purchase or the pantry.
- **Purchases are idempotent.** The client creates an event id for each checkout. A network retry
  reuses it, so the server credits the pantry once, and a second real purchase gets a new id. The
  pantry gets the whole pack amount that was bought, not only the shortfall. A price is saved as
  "paid" only if the user typed it. The shelf price is shown as an estimate.
- **Parsing is hand-written.** `BilkaWireParsing` decodes the responses without generated
  serializers, so unknown fields are ignored and both naming styles are accepted (the server uses
  camelCase in some responses and snake_case in others).

## Nightly price refresh

`bilka-nightly-refresh` keeps chosen products' prices current for users who opted in.

- `pg_cron` calls it every hour. The SQL only sends the request when the local time in
  `Europe/Copenhagen` is 05, 06 or 07, and the worker checks the time again, so daylight saving does
  not shift the run.
- The cron job authenticates with a token that lives in Vault. The function receives it in a request
  header, hashes it, and compares it in constant time with a SHA-256 digest fetched through a
  service-role-only RPC.
- One verified user per household is used. Unverified keys are ignored. Spending still goes through
  the same quota reservation as the manual calls.

## Tests

- `supabase/functions/**/*_test.ts`: quota handling, auth, ranking, barcode validation (including the
  GS1 check digit), price updates and the allowlist. Run with `deno test`.
- `supabase/tests/bilka_*_contract.sql`: privileges, RLS and the purchase transaction. Each script
  runs inside a transaction and rolls back, so it is safe to run on a staging database.

## Deploying

1. Apply the migrations in `supabase/migrations` in order.
2. Run the contract scripts in `supabase/tests` against staging.
3. Before applying `20261010140000_bilka_nightly_prices.sql`, replace `<project-ref>` in it with
   your project reference. It schedules the cron job, which stays inert until the later Vault
   migration installs the token.
4. Deploy `bilka-prej` with JWT verification **on**. Deploy `bilka-nightly-refresh` with it **off**
   (the cron call has no user JWT, and the function requires the cron token instead).
