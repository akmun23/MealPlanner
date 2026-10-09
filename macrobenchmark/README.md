# On-device performance benchmarks

These benchmarks are separate from the app and do **not** run on the usual GitHub CI emulator. They measure a non-debuggable, release-like `benchmark` variant on a physical phone. The app currently keeps its release shrinking hotfix disabled; the benchmark variant deliberately matches that.

**Warning:** The benchmark app uses the same application ID as MealPlanner but a debug signing key. Installing it over a Play/release-signed copy may require uninstalling the original app, which would erase its local Room/offline queue data. **Use a spare test device or backed-up installation; do not uninstall your primary copy merely to benchmark.**

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
