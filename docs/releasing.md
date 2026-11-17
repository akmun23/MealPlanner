# Releasing

Notes for building a Play release. Nothing here needs secrets in the repository: signing keys stay
on my machine and the Supabase values come from `local.properties`.

## Order of operations

1. Back up the production data if the release includes a migration.
2. Apply new files in `supabase/migrations` to the project, then run the matching scripts in
   `supabase/tests` on staging (they roll back).
3. Deploy changed Edge Functions.
4. Build and sign the bundle, test it on a phone, then upload it.

Migrations go **before** the app, because a new app that queries a table the database does not have
yet will fail. Older app versions also have to keep working against the newer schema for as long as
they are installed, so changes are additive (new tables, nullable columns).
`20261013090000_recipe_estimate_null_compatibility.sql` fixes a case where an older client sent a
`null` that a new constraint did not accept.

## Build variants

| Variant | Use |
|---|---|
| `debug` | Day-to-day work. Application id has a `.debug` suffix, so it installs beside the release app. |
| `release` | What goes to Play. R8 shrinking, optimisation and resource shrinking are on (`optimization.enable`), native debug symbols are requested at `FULL`. |
| `benchmark` | Release settings, signed with the debug key, for the macrobenchmarks. Never upload it. |
| `unoptimized` | Release without R8. Only exists to measure how much R8 removes. Never upload it. |

R8 keep rules live in `app/src/main/keepRules/rules.keep`. The rule is to add a narrow keep only when
a real crash needs one, and not to switch off shrinking or add blanket keeps to hide a problem.

## Checks before uploading

```bash
bash ./gradlew :app:testDebugUnitTest :app:assembleRelease :app:bundleRelease
python3 scripts/check_release_bundle_metadata.py app/build/outputs/bundle/release/app-release.aab
```

`check_release_bundle_metadata.py` fails if the bundle has no R8 mapping and warns if it ships native
libraries without debug symbols. CI runs both of these, plus `scripts/check_dex_optimization.py`,
which compares the release APK with the unoptimized one so a regression in shrinking shows up as a
number.

Keep `mapping.txt` (`app/build/outputs/mapping/release/`) for every released version code. It is the
only way to read a release stack trace, and the next build overwrites it.

An R8 build once crashed on startup on a real phone, so a release build gets installed and used
before it is promoted: cold start, login, offline use, a synced household, photos, and backup and
restore. Watch `adb logcat` for `FATAL EXCEPTION` and `ClassNotFoundException`. If something breaks,
fix the specific keep rule it needs.

## Signing

```bash
scripts/sign_release_bundle.sh app-release.aab /path/to/upload-keystore.jks upload-alias
```

The script signs a copy with `jarsigner` and verifies it. The key and its passwords are typed at the
terminal and never stored.

## Play Console warnings

Play can warn that an upload has no deobfuscation file or no native debug symbols. They are
different files. The bundle checker above covers the first. The second can only be produced if the
native libraries still contain symbols. If a dependency ships stripped `.so` files, Gradle cannot
recreate them, and the warning stays.

For an already uploaded bundle, open *App bundle explorer*, pick that exact version code, and upload
the `mapping.txt` from the same build. Changing build settings afterwards does not change an old
upload.
