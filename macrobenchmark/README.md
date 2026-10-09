# On-device performance benchmarks

These benchmarks are separate from the app and do **not** run on the usual GitHub CI emulator. They measure a non-debuggable, release-like `benchmark` variant on a physical phone. The production release keeps the previous startup-stability hotfix (R8 disabled). **The benchmark variant now deliberately enables R8** so startup and scrolling can be compared before any production rollout.

**Warning:** The benchmark app uses the same application ID as MealPlanner but a debug signing key. Installing it over a Play/release-signed copy may require uninstalling the original app, which would erase its local Room/offline queue data. **Use a spare test device or backed-up installation; do not uninstall your primary copy merely to benchmark.**

## DEX optimization staging (R8)

Android Gradle Plugin 9.4 uses `optimization { enable = true }`, enabling both
R8 bytecode optimization/shrinking and optimized resource shrinking. The
non-debuggable `benchmark` build uses it; **production `release` does not**
because a previous production-optimized build crashed at startup on a phone.

```bash
bash ./gradlew :app:assembleRelease :app:assembleBenchmark
python3 scripts/check_dex_optimization.py \
  --release app/build/outputs/apk/release/app-release-unsigned.apk \
  --optimized app/build/outputs/apk/benchmark/app-benchmark.apk \
  --report dex-optimization-report.json
```

Use the actual APK filenames if your AGP version generates different names.
CI performs this measurement automatically and saves the R8 obfuscation mapping
and report for 14 days. DEX byte savings **do not prove** smoother scrolling
or even runtime correctness.

**Required before enabling R8 for production:**

- Install the optimized benchmark APK on a separate test phone; confirm it
  launches repeatedly from a cold start and does not crash.
- Exercise local-only mode, offline saves/reloads, recipe and ingredient
  creation, pantry sorting, shopping, JSON backup/restore, and tab navigation.
- Separately test a real signed-in household, Supabase sync/realtime, image
  signing/loading, login/deep links and app restart after process death.
- Record macrobenchmark cold-start, tab frame timing, pantry/recipe scrolling
  and crash logs. Compare to an otherwise identical unoptimized test build
  on the same device and dataset.
- When validated, switch production `release.optimization.enable` to
  `true`, ship as a new versionCode and retain the generated mapping file
  for deobfuscating any R8-related stack traces.

Do not make broad `-keep class **` or `-dontoptimize` additions: fix only
missing reflective entry points proven by crashes and keep library-supplied
rules intact. Android/Room/WorkManager manifest entries and serializers need
real-device validation after R8.

## Compile the harness

```bash
bash ./gradlew :app:assembleBenchmark :macrobenchmark:assembleBenchmark
```

## Run on a physical Android device

Connect the test phone over USB with developer mode enabled.

```bash
bash ./gradlew :macrobenchmark:connectedBenchmarkAndroidTest
```

`coldStartup` works at the login gate as well as inside a household. `tabNavigationFrameTiming` requires a previously signed-in and prepared household on the test installation; if you are on the login screen it reports a clear failure rather than fake zero-jank metrics.

Record baseline median startup time and frame-time P90/P95 in Android Studio, and keep the generated JSON/Perfetto traces under your own results archive. Repeat each benchmark on the same device / OS / dataset at a consistent temperature and compare PRs only under the same conditions. Emulators are useful for testing benchmark plumbing but **not** trustworthy performance measurements.

Official guide: https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview
