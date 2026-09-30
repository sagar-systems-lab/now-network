#!/usr/bin/env bash
set -Eeuo pipefail

URL=""
RUNS=20
OUT_DIR=""
BEARER_TOKEN=""

usage() {
  cat >&2 <<'EOF'
usage:
  sample-http-latency.sh --url <https://...> [--runs <n>] [--bearer-token <token>] [--out <dir>]

The token is used only as an Authorization header and is never written to the output.
EOF
  exit 2
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --url) URL="$2"; shift 2 ;;
    --runs) RUNS="$2"; shift 2 ;;
    --bearer-token) BEARER_TOKEN="$2"; shift 2 ;;
    --out) OUT_DIR="$2"; shift 2 ;;
    *) usage ;;
  esac
done

[[ "$URL" =~ ^https?:// ]] || usage
[[ "$RUNS" =~ ^[1-9][0-9]*$ ]] || usage

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
OUT_DIR="${OUT_DIR:-performance-results/http-$timestamp}"
mkdir -p "$OUT_DIR"

printf 'metric,value_ms\n' > "$OUT_DIR/http.csv"
printf 'run,status_code,time_total_s\n' > "$OUT_DIR/http-raw.csv"

headers=()
if [ -n "$BEARER_TOKEN" ]; then
  headers=(-H "Authorization: Bearer $BEARER_TOKEN")
fi

for i in $(seq 1 "$RUNS"); do
  result="$(curl     --silent     --show-error     --output /dev/null     --connect-timeout 10     --max-time 20     "${headers[@]}"     --write-out '%{http_code},%{time_total}'     "$URL")"

  status="${result%%,*}"
  seconds="${result#*,}"

  if ! [[ "$status" =~ ^[0-9]{3}$ ]]; then
    echo "invalid HTTP status on run $i: $status" >&2
    exit 1
  fi

  ms="$(awk -v value="$seconds" 'BEGIN { printf "%.3f", value * 1000 }')"
  printf '%s,%s,%s\n' "$i" "$status" "$seconds" >> "$OUT_DIR/http-raw.csv"
  printf 'client_http_total,%s\n' "$ms" >> "$OUT_DIR/http.csv"
done

deno run   --allow-read="$OUT_DIR/http.csv"   --allow-write="$OUT_DIR/http-summary.json"   scripts/performance/summarize-latency.ts   --input "$OUT_DIR/http.csv"   --output "$OUT_DIR/http-summary.json"

cat > "$OUT_DIR/metadata.txt" <<EOF
recorded_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
git_sha=$(git rev-parse HEAD 2>/dev/null || echo unknown)
url=$URL
runs=$RUNS
authorization_header=$([ -n "$BEARER_TOKEN" ] && echo present || echo absent)
EOF

echo "HTTP observations written to: $OUT_DIR"
