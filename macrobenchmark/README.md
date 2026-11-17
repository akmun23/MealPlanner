# Macrobenchmarks

Startup and scroll benchmarks that run on a real phone against the `benchmark` build type: the
release configuration with R8 on, signed with the debug key so it can be installed for testing.

> **Use a spare phone.** The benchmark build has the release application id but a different
> signature. Installing it over a Play-signed copy means uninstalling that copy first, which wipes the
> local database and any edits that have not synced yet.

## What is measured

| Test | Measures |
|---|---|
| `coldStartup` | Cold start time, at the login screen or inside a household |
| `tabNavigationFrameTiming` | Frame timing while switching tabs |
| `weekScrollFrameTiming` and the other `*ScrollFrameTiming` tests | Frame timing while scrolling Madplan, Opskrifter, Lager and Indkøb |
| `subpageNavigationSmoke`, `existingItemSubpagesSmoke` | Opening sub-pages and item pages without crashing |

`tabNavigationFrameTiming` needs a household that is already signed in and populated. On the login
screen it fails with a clear message rather than reporting empty, jank-free numbers.

## Running

Connect the phone with USB debugging on, then:

```bash
bash ./gradlew :macrobenchmark:connectedBenchmarkAndroidTest
```

To only check that the harness compiles:

```bash
bash ./gradlew :app:assembleBenchmark :macrobenchmark:assembleBenchmark
```

## Reading the results

- Compare runs on the same phone, the same OS version and the same household data. A nearly empty
  list scrolls well and tells you nothing.
- Look at the P90, P95 and P99 frame times and the Perfetto trace, not just the average. A frame
  that takes longer than the display's budget (about 16.7 ms at 60 Hz) shows up as jank.
- Run once with a cold image cache and once with a warm one, and compare them separately. Photo
  URLs are signed on demand, so the first visit can show placeholders for a moment.
- Emulators are fine for checking the setup but are not a trustworthy source of timings, and a
  debug build is not comparable to the release-like one.

See the [Android macrobenchmark guide](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)
for how to read the output.
