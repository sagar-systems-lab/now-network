# Performance measurement

NOW performance work starts with repeatable measurements. The repository keeps measurement tooling separate from product behavior so optimizations can be justified by traces and samples rather than assumptions.

## Android benchmark build

The app exposes a `benchmark` build type that inherits the release configuration, remains non-debuggable, is profileable, and uses local debug signing only so it can be installed on development devices.

Build it with:

```bash
./gradlew :app:assembleBenchmark
```

Expected artifact:

```text
apps/android/build/outputs/apk/benchmark/app-benchmark.apk
```

This build is for local profiling and benchmark tooling. It is not a distributable release candidate.

## Device baseline sampler

For a connected Android device:

```bash
bash scripts/performance/android-device-baseline.sh \
  --apk apps/android/build/outputs/apk/benchmark/app-benchmark.apk \
  --runs 10
```

The sampler records:

- device/build metadata
- APK SHA-256 and size
- repeated process-dead shell startup observations
- raw memory snapshot
- raw frame statistics

The shell startup value is intentionally named `cold_shell_ttid_proxy`. It is a supplementary observation only; formal startup and frame acceptance uses Android Macrobenchmark.

Performance outputs are written below `performance-results/`, which is intentionally untracked.

## HTTP latency sampler

Use the generic sampler for hosted or local endpoints:

```bash
bash scripts/performance/sample-http-latency.sh \
  --url https://example.test/health \
  --runs 20
```

For authenticated routes, provide a short-lived bearer token with `--bearer-token`. The token is never written to the output.

The sampler preserves raw observations and produces a JSON summary containing sample count, min, p50, p95/p99 when the sample count supports them, max, and the sorted raw sample set.

## PostgreSQL/PostGIS plans

Capture execution plans for the two hot geospatial list functions:

```bash
DB_CONTAINER=<postgres-container> \
  bash scripts/performance/capture-db-plans.sh \
    --lat 30.0 \
    --lng 77.0 \
    --radius-m 3000 \
    --limit 25
```

Or use a secure operator-side `DATABASE_URL`.

The command records `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` output for:

- nearby states
- nearby earning opportunities

Do not place production database credentials in shell history, issue trackers, screenshots, or committed files.

## Statistical helper

The latency summarizer accepts:

```csv
metric,value_ms
client_http_total,125.4
client_http_total,132.8
```

Run:

```bash
deno run --allow-read --allow-write \
  scripts/performance/summarize-latency.ts \
  --input samples.csv \
  --output summary.json
```

Raw observations are preserved. Tail percentiles are withheld for very small sample sets instead of presenting false precision.

## Qualification sequence

Use the tooling in this order:

1. capture baseline metadata and raw observations
2. capture hot query plans
3. run formal Android Macrobenchmark on the physical reference device
4. generate/measure Baseline Profile coverage for critical journeys
5. investigate traces before changing implementation
6. rerun the same measurement set after each material optimization

Benchmark or profiling results are environment-specific. Always preserve the device, build, network, sample count, and APK/source identity with the result.


## Formal Android Macrobenchmark

The `:benchmark` module measures the release-like `benchmark` target from outside the app process.

Run formal benchmarks on a physical Android device:

```bash
./gradlew :benchmark:connectedCheck
```

The startup suite records:

- cold startup without AOT compilation
- cold startup with the Baseline Profile
- warm startup with the Baseline Profile
- hot startup with the Baseline Profile

The top-level navigation benchmark records `FrameTimingMetric` while traversing NOW → EARN → ACTIVITY → NOW.

Macrobenchmark JSON and Perfetto traces are written under the benchmark module's connected-test additional-output directory. Preserve the exact APK/source SHA and device metadata with every accepted result.

Do not use emulator timing as release-performance evidence.

## Baseline and Startup Profiles

The benchmark module also contains two profile generators:

- startup-only profile collection, included in the Startup Profile
- NOW/EARN/ACTIVITY critical navigation, included in the Baseline Profile but not the Startup Profile

Generate profiles on a connected API 33+ physical device with:

```bash
./gradlew :app:generateBaselineProfile \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=BaselineProfile
```

Generated profile files are consumed by the app through the Baseline Profile Gradle plugin.

After generation, rebuild the benchmark/release target and compare the cold no-compilation benchmark with the cold Baseline-Profile benchmark. Keep profile generation and performance measurement as separate evidence steps.
