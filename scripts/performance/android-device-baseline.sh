#!/usr/bin/env bash
set -Eeuo pipefail

APK=""
SERIAL="${ANDROID_SERIAL:-}"
RUNS=10
OUT_DIR=""
PACKAGE_NAME="com.sagarsystemslab.nownetwork"
ACTIVITY=".MainActivity"

usage() {
  cat >&2 <<'EOF'
usage:
  android-device-baseline.sh --apk <benchmark.apk> [--serial <adb-serial>] [--runs <n>] [--out <dir>]

This records repeatable shell-level startup observations and device/resource metadata.
It is a supplementary baseline, not a replacement for Macrobenchmark.
EOF
  exit 2
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --apk) APK="$2"; shift 2 ;;
    --serial) SERIAL="$2"; shift 2 ;;
    --runs) RUNS="$2"; shift 2 ;;
    --out) OUT_DIR="$2"; shift 2 ;;
    *) usage ;;
  esac
done

[ -n "$APK" ] || usage
[ -s "$APK" ] || { echo "APK not found: $APK" >&2; exit 1; }
[[ "$RUNS" =~ ^[1-9][0-9]*$ ]] || { echo "runs must be a positive integer" >&2; exit 2; }

ADB=(adb)
if [ -n "$SERIAL" ]; then
  ADB+=( -s "$SERIAL" )
fi

"${ADB[@]}" get-state >/dev/null

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
OUT_DIR="${OUT_DIR:-performance-results/android-$timestamp}"
mkdir -p "$OUT_DIR"

component="$PACKAGE_NAME/$PACKAGE_NAME$ACTIVITY"

"${ADB[@]}" install -r -t "$APK" >/dev/null

model="$("${ADB[@]}" shell getprop ro.product.model | tr -d '\r')"
android_version="$("${ADB[@]}" shell getprop ro.build.version.release | tr -d '\r')"
sdk="$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
soc="$("${ADB[@]}" shell getprop ro.soc.model | tr -d '\r')"
[ -n "$soc" ] || soc="$("${ADB[@]}" shell getprop ro.hardware | tr -d '\r')"
ram_kb="$("${ADB[@]}" shell awk '/MemTotal/ {print $2}' /proc/meminfo | tr -d '\r')"
battery_level="$("${ADB[@]}" shell dumpsys battery | awk -F': ' '/level:/ {print $2; exit}' | tr -d '\r')"
battery_temp_tenths_c="$("${ADB[@]}" shell dumpsys battery | awk -F': ' '/temperature:/ {print $2; exit}' | tr -d '\r')"
thermal_status="$("${ADB[@]}" shell cmd thermalservice get-current-thermal-status 2>/dev/null | tr -d '\r' || true)"
display_info="$("${ADB[@]}" shell dumpsys display 2>/dev/null | grep -m1 -E 'refreshRate|fps|modeId' | tr -d '\r' || true)"
git_sha="$(git rev-parse HEAD 2>/dev/null || echo unknown)"
apk_sha256="$(sha256sum "$APK" | awk '{print $1}')"
apk_size_bytes="$(stat -c %s "$APK")"

export NOW_PERF_MODEL="$model"
export NOW_PERF_ANDROID_VERSION="$android_version"
export NOW_PERF_SDK="$sdk"
export NOW_PERF_SOC="$soc"
export NOW_PERF_RAM_KB="$ram_kb"
export NOW_PERF_BATTERY_LEVEL="$battery_level"
export NOW_PERF_BATTERY_TEMP="$battery_temp_tenths_c"
export NOW_PERF_THERMAL="$thermal_status"
export NOW_PERF_DISPLAY="$display_info"
export NOW_PERF_GIT_SHA="$git_sha"
export NOW_PERF_APK_SHA="$apk_sha256"
export NOW_PERF_APK_SIZE="$apk_size_bytes"
export NOW_PERF_RUNS="$RUNS"
export NOW_PERF_PACKAGE="$PACKAGE_NAME"
export NOW_PERF_METADATA_OUT="$OUT_DIR/device-metadata.json"

deno eval --allow-env --allow-write="$OUT_DIR/device-metadata.json" '
const read = (name) => Deno.env.get(name) ?? "";
const payload = {
  recorded_at: new Date().toISOString(),
  measurement_kind: "adb_shell_observation",
  formal_macrobenchmark: false,
  device: {
    model: read("NOW_PERF_MODEL"),
    android_version: read("NOW_PERF_ANDROID_VERSION"),
    sdk: Number(read("NOW_PERF_SDK")),
    soc: read("NOW_PERF_SOC"),
    ram_kb: Number(read("NOW_PERF_RAM_KB")),
    display_info: read("NOW_PERF_DISPLAY"),
    battery_level: Number(read("NOW_PERF_BATTERY_LEVEL")),
    battery_temperature_tenths_c: Number(read("NOW_PERF_BATTERY_TEMP")),
    thermal_status: read("NOW_PERF_THERMAL"),
  },
  build: {
    package_name: read("NOW_PERF_PACKAGE"),
    git_sha: read("NOW_PERF_GIT_SHA"),
    apk_sha256: read("NOW_PERF_APK_SHA"),
    apk_size_bytes: Number(read("NOW_PERF_APK_SIZE")),
    build_type: "benchmark",
  },
  startup_runs: Number(read("NOW_PERF_RUNS")),
};
await Deno.writeTextFile(
  read("NOW_PERF_METADATA_OUT"),
  JSON.stringify(payload, null, 2) + "\n",
);
'

printf 'metric,value_ms\n' > "$OUT_DIR/startup.csv"

for i in $(seq 1 "$RUNS"); do
  "${ADB[@]}" shell am force-stop "$PACKAGE_NAME"
  sleep 0.35

  output="$("${ADB[@]}" shell am start -W -n "$component")"
  total_ms="$(awk -F': ' '/TotalTime:/ {print $2}' <<<"$output" | tail -n1 | tr -d '\r[:space:]')"

  if ! [[ "$total_ms" =~ ^[0-9]+$ ]]; then
    echo "unable to parse TotalTime on run $i" >&2
    echo "$output" >&2
    exit 1
  fi

  printf 'cold_shell_ttid_proxy,%s\n' "$total_ms" >> "$OUT_DIR/startup.csv"
done

deno run   --allow-read="$OUT_DIR/startup.csv"   --allow-write="$OUT_DIR/startup-summary.json"   scripts/performance/summarize-latency.ts   --input "$OUT_DIR/startup.csv"   --output "$OUT_DIR/startup-summary.json"

"${ADB[@]}" shell dumpsys meminfo "$PACKAGE_NAME" > "$OUT_DIR/meminfo.txt"
"${ADB[@]}" shell dumpsys gfxinfo "$PACKAGE_NAME" framestats > "$OUT_DIR/gfxinfo-framestats.txt"

echo "performance observation written to: $OUT_DIR"
echo "NOTE: cold_shell_ttid_proxy is supplementary. Formal startup/frame gates require Macrobenchmark."
