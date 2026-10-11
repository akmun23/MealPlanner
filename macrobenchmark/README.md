# On-device performance benchmarks

These benchmarks run separately from the normal application on a physical Android phone. They measure a non-debuggable, release-like `benchmark` variant; the Play `release` variant now **also has R8 enabled**. The benchmark variant inherits production's optimization configuration but is signed with a debug key.

**Warning:** The benchmark app uses the same application ID as MealPlanner and a different signing key. Installing it over a Play/release-signed copy may require uninstalling the original, which would erase local Room/offline-queue data. **Use a spare test phone or an independently backed-up installation. Never uninstall the only copy just to benchmark.**

## R8 in production (AGP 9.4)

`app/build.gradle.kts` enables `release.optimization.enable = true`. AGP 9.3+ activates code shrinking, optimization, obfuscation, and optimized resource shrinking from this setting; its default Android keep rules are applied automatically. Project-specific R8 rules, if evidence shows they are needed, belong under `app/src/main/keepRules/*.keep`. Avoid broad `-keep`, `-dontoptimize` or `-dontobfuscate` rules: they can prevent the app from meeting Play Console's DEX optimization goals.

A separate, **non-distributable** `unoptimized` build type provides a valid comparison baseline. The `benchmark` variant remains R8-enabled for on-device frame timing.

Build the three artifacts locally without spending GitHub Actions minutes:

```bash
./gradlew :app:assembleUnoptimized :app:assembleRelease :app:bundleRelease :app:assembleBenchmark
python3 scripts/check_dex_optimization.py \
  --baseline app/build/outputs/apk/unoptimized/app-unoptimized.apk \
  --optimized app/build/outputs/apk/release/app-release-unsigned.apk \
  --report dex-optimization-report.json
```

Adjust the APK filenames if the Android Gradle Plugin produces different names. The DEX report shows the **size difference**, not whether Google Play's distinct obfuscation/optimization/shrinking percentages meet their thresholds.

The production mapping file must be present and nonempty:

```bash
test -s app/build/outputs/mapping/release/mapping.txt
```

Archive that `mapping.txt` **for each release version**, alongside the final Play App Bundle (`app/build/outputs/bundle/release/*.aab`). The map is necessary to deobfuscate crashes; Play's app bundle upload usually carries the associated code mapping automatically. Generate and sign the final AAB with your actual upload key using Android Studio or a secured release system; the repository intentionally contains no signing credentials.

**Previous R8 startup issue:** The project previously disabled optimized production release after a physical-device startup crash. Its root cause was never established from a trace. This PR configures R8 to meet the upcoming Play optimization requirement, but that crash may recur. **Do not promote the AAB to production until a correctly signed release-like build has been installed and fully tested on a spare phone, including startup, login and Supabase sync.**

### Test checklist before Play rollout

- Confirm cold starts, repeated launches and no `FATAL EXCEPTION` or `ClassNotFoundException` in `adb logcat` with R8 active.
- Test local-only mode, offline persistence, Room, recipe creation, pantry/shopping, backup/restore, authentication, login callback and process restart.
- Test a real signed-in household, Supabase serialization, Edge-function calls, realtime updates, Coil image URLs and cold/warm scrolling.
- Read the release `mapping.txt` for deobfuscating stack traces. If anything crashes, fix only the specific reflection/serialization keep rules supported by the crash; do not disable shrinking to mask it.
- Compare `dex-optimization-report.json` to the DEX percentage figures reported by Play Console under App bundle explorer. Those numbers are **not the same metric**.
- Begin with an **internal testing** Play track and inspect Android vitals before staged production rollout.

The optional GitHub workflow `R8 emulator runtime smoke (manual)` can also be used once the Actions quota resets, but it verifies only the login screen and not a full release flow.

## Compile the harness

```bash
bash ./gradlew :app:assembleBenchmark :macrobenchmark:assembleBenchmark
```

## Run on a physical Android device

Connect the test phone over USB with developer mode enabled.

```bash
bash ./gradlew :macrobenchmark:connectedBenchmarkAndroidTest
```

The frame benchmarks now cover **Madplan**, **Opskrifter**, **Lager** and **Indkøb**. Use the same populated household and scroll the same items for before/after runs; otherwise a mostly empty list gives misleading numbers. On a 60 Hz display, frames above ~16.7 ms can appear janky (for 90/120 Hz, the budget is lower). Examine the P90/P95/P99 frame-time results and Perfetto traces rather than the average alone. Do not compare Android Studio debug builds with release-like builds: Compose debuggability, compiler optimizations and R8 settings differ.

**Photo loading trade-off:** broad background prefetch of up to 150 images was removed to prevent concurrent signing/network/decoding load during flings. The first visit may show fallbacks until visible photos load. Repeat the benchmark once with a cold image cache and once warm; compare each condition separately.

`coldStartup` works at the login gate as well as inside a household. `tabNavigationFrameTiming` requires a previously signed-in and prepared household on the test installation; if you are on the login screen it reports a clear failure rather than fake zero-jank metrics.

Record baseline median startup time and frame-time P90/P95 in Android Studio, and keep the generated JSON/Perfetto traces under your own results archive. Repeat each benchmark on the same device / OS / dataset at a consistent temperature and compare PRs only under the same conditions. Emulators are useful for testing benchmark plumbing but **not** trustworthy performance measurements. A debug APK is not a substitute for the production-optimized app.

Official guide: https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview
