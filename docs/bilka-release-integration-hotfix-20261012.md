# BilkaToGo hotfix — release integration (12 Oct 2026)

## Incident and evidence

The latest Android release shows `Produktoversigten kunne ikke læses` and cannot browse or add
further Bilka products. **Historical Supabase catalogue data do not prove the current APK works.**
At 08:34–08:35 UTC on 2026-10-12 the deployed `bilka-prej` Edge Function returned HTTP 200 to
requests during the incident. The old Android screen's generic message came from JSON decoding after
a successful request, not directly from the HTTP status.

There was also a **confirmed API version mismatch**: the Android client sends action `ean-lookup`,
but production Edge Function version **18** did not implement that action. The deployed server was
upgraded to **version 19** on 2026-10-12, from the current repository's existing
`supabase/functions/bilka-prej` code. Deployment has been verified: ACTIVE / version 19 / JWT
verification enabled, and `index.ts` includes `ean-lookup`. This does not require changing the
household's key or stored product choices.

The original JSON exception was not captured from the installed APK, so **R8 as the root cause is a
hypothesis, not a confirmed diagnosis**. The Android change deliberately eliminates generated
serializers from Bilka wire decoding to address this class of failure.

## Android hotfix

- `BilkaWireParsing.kt` decodes `discovery-status` and `catalog` into DTOs by field name, without
  `kotlinx.serialization.decodeFromJsonElement<DTO>()`. Handles mixed snake_case/camelCase, null
  optional fields, unknown future enums, and isolated malformed rows.
- `BilkaShoppingModels.kt` directly decodes weekly Bilka purchase context, preserving
  already-recorded purchases and defaults.
- Preserves available portions of responses and shows useful errors.
- Error messages distinguish server/client action mismatch and missing priced stock.
- Unit tests contain realistic schema examples and regression cases. Builds and real-device tests
  **have not run** in this environment, due to unavailable Android SDK and exhausted GitHub Actions
  minutes.
- App `versionCode` incremented from **7 to 8**, `versionName` from **1.2.3 to 1.2.4** for a new
  Play internal test release. Confirm versionCode 8 hasn't already been used in Play Console; if it
  has, increment further before building.

## Device acceptance checklist

**Do not clear app data, uninstall, remove Prej credentials, or discard any offline conflict
queue.** Install an upgrade over the same signed package through Play internal testing, preserving
local state.

1. In Settings → Sync, confirm the outbox is drained or investigate any remaining conflicts
   separately.
2. In Settings → Mine BilkaToGo-produkter, open *Alle*. Validate rows appear and an already chosen
   product is named.
3. Open a chosen/ready ingredient. Validate thumbnails, brand, size, and price display; select and
   save a priced product, and verify it still appears on return.
4. Open `Mangler dit mærke? Søg i BilkaToGo`, perform a name search on one known item, and confirm
   new results appear; this **may consume a Prej API call**.
5. Test EAN lookup using a real BilkaToGo EAN; unknown EANs may correctly return no match, and new
   lookups may consume a Prej API call.
6. On Shopping, validate the product annotation and purchase-to-pantry flow **only once** for a test
   ingredient; do not repeat if uncertain, to avoid duplicates.
7. Repeat steps 2–3 using the **optimized release**, not only Debug, and compare app-side errors and
   Logcat if the issue remains.

If the catalog still fails: collect `adb logcat` around the action, filtering `AndroidRuntime` and
serialization exceptions. Do not log Authorization headers, Prej API keys, household data, or entire
raw API responses. The app now distinguishes missing top-level fields from missing individual
records.

## Deploy coordination

Server is already version 19. This Android PR is independent of PR #85 (Play Console symbol
archives). To ship the client: merge, build a **properly signed** release with real `SUPABASE_URL`
and `SUPABASE_PUBLISHABLE_KEY` in the *local* `local.properties`, test via Play internal testing,
then promote cautiously. The repository's GitHub Actions placeholder keys and generated CI build
artefacts are **not** appropriate for production upload.
