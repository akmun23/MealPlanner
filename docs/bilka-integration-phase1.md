# Bilka integration — phase 1 (schema + models)

## Scope and review

This PR is the **first of seven phases**. It creates the data contracts needed for the subsequent Prej server integration, without changing existing meals, ingredients, shopping rows, or Android screens. There are **no API calls, no API key, and no scheduled jobs** in this phase.

The PR is intended for review/merge **before** the migration is applied to the hosted Supabase database.

### Decisions retained from the product discussion

- The scope is **Bilka only**, not BilkaToGo or other chains.
- Only **two members of the same existing household** use the app, sharing defaults.
- Users choose **an exact product ID**, including its brand and pack size. Never swap automatically to a cheaper product.
- Price, offer, availability, and (where licensed) image metadata belong to the cached product, and can change without affecting defaults.
- Product suggestions are read-only to the mobile client; selected defaults are household-editable.
- One-off alternative purchases are tied to a **specific weekly plan**, never to the household default.
- Purchased quantities are **whole packages**, with the actual purchased amount held separately from the recipe requirement (supports freezer/pantry restocking).
- Unknown package measurements remain nullable; a household may explicitly override the package base amount.
- Catalog discovery and Prej quota accounting are server-owned. Data access obeys RLS.
- Subsequent phases will use a queue for **new text searches** and batches of <=500 **already-known product IDs** for price refresh. These must not be confused.

### New tables

| Table | Client permissions | Purpose |
|---|---|---|
| `bilka_products` | Authenticated read only | Exact SKU, brand, package quantity, image URL, package price, unit price, availability |
| `ingredient_bilka_candidates` | Household read only | Possible products found for a given ingredient |
| `ingredient_bilka_defaults` | Household read/write | Exactly one stable product ID per household ingredient |
| `shopping_product_overrides` | Household read/write | Different product bought for one plan / ingredient |
| `bilka_discovery_jobs` | Household read only | Backend search queue status (one job per ingredient) |
| `prej_api_usage` | Server only | Daily API budget tracking, by UTC date |

All public tables have RLS enabled, grants restricted, and composite foreign keys to guard household boundaries. `private.is_household_member()` is the existing shared authorization primitive.

### Migration and verification

1. Review `supabase/migrations/20261010093000_bilka_phase1.sql`.
2. Run Android tests: `./gradlew :app:testDebugUnitTest`, and build with `./gradlew :app:assembleDebug`. The existing GitHub PR workflow runs both, including release/benchmark checks.
3. Apply migration to an isolated Supabase test database before production. Verify every new table exists, all six are RLS-enabled, and that:
   - `anon` cannot select or mutate any new table.
   - a non-member cannot read or change another household's defaults, candidates, jobs, or shopping overrides.
   - both authorized members can update one shared default.
   - authenticated users cannot write to the product catalog, candidate list, job queue, or quota table.
   - attempted cross-household ingredient/plan references violate composite foreign keys.
   - changing `bilka_products.price_ore` does not modify `ingredient_bilka_defaults.product_id`.
4. **Do not put the Prej key in GitHub, Gradle, Android, migrations or SQL.** Configure a server-side secret only when the Edge Function is implemented in phase 2.

This migration was intentionally **not applied to the production Supabase project** as part of this PR.

## Review of existing `app_feedback` (8 suggestions, 10 October 2026)

| Existing feedback | Relationship to Bilka work | Decision |
|---|---|---|
| Default purchasing amounts: e.g. milk 1 L, avocado 6-pack, spice packaging | **Directly relevant** | Phase 1 persists exact package sizes and optional manually corrected base amounts. Phase 6 uses them to calculate whole packages. |
| Tandem meals: stegt flæsk today, biksemad tomorrow from leftovers | Separate planner feature | Keep as follow-up, not in Bilka PR. Requires sequencing/leftover planning. |
| Structured recipe steps, separate from recipe notes and URLs | Separate recipe editor feature | Keep as follow-up; would require recipe steps table and UI. |
| Ingredients shown in recipes but never bought (e.g. water, salt, pepper) | Related to shopping logic but **not** a Bilka SKU requirement | Exclude from automatic product discovery in future when a purchase-exclusion rule exists. Do not silently change existing shopping behavior. |
| Pantry-required ingredients must not count as protein | Separate planner/scoring rule | Investigate independently. |
| Lock the current meal plan, ask for confirmation before generating | Separate planner UX | Keep as independent change. |
| Navigate to future calendar weeks | Separate planner navigation | Keep as independent change. |
| Lock an individual dish | May overlap current day locking in planner | Verify current UI and expected semantics before another implementation. |

None of these eight feedback rows has been modified, marked completed, or deleted.

## Next phases (not part of this PR)

- **Phase 2:** Prej server-side client, API credential via Supabase secret, shared quota reservation and batch refresh client.
- **Phase 3:** Search queue + candidate discovery using Bilka category queries and individual searches where needed.
- **Phase 4:** Compose product selector with photos, brand, name, sizes, unit and package prices.
- **Phase 5:** Nightly price updates that never change product selection.
- **Phase 6:** Shopping-list product cards, whole-pack calculations, one-off substitutes, pantry quantities.
- **Phase 7:** Cross-household isolation, offline/retry, end-to-end integration tests.
