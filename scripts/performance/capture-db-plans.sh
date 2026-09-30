#!/usr/bin/env bash
set -Eeuo pipefail

DB_CONTAINER="${DB_CONTAINER:-}"
DATABASE_URL="${DATABASE_URL:-}"
DB_NAME="${DB_NAME:-now_test}"
LAT=""
LNG=""
RADIUS_M="3000"
LIMIT="25"
ACTOR_ID="00000000-0000-4000-8000-000000000001"
OUT_DIR=""

usage() {
  cat >&2 <<'EOF'
usage:
  capture-db-plans.sh --lat <latitude> --lng <longitude> [--radius-m <meters>] [--limit <n>] [--actor-id <uuid>] [--out <dir>]

Database connection:
  DB_CONTAINER=<container-id> [DB_NAME=now_test]
  or
  DATABASE_URL=<postgres-connection-string>
EOF
  exit 2
}

psql_now() {
  if [ -n "$DB_CONTAINER" ]; then
    docker exec "$DB_CONTAINER" psql -XAtq -U postgres -d "$DB_NAME" -v ON_ERROR_STOP=1 "$@"
    return
  fi

  if [ -n "$DATABASE_URL" ]; then
    psql -XAtq "$DATABASE_URL" -v ON_ERROR_STOP=1 "$@"
    return
  fi

  echo "DB_CONTAINER or DATABASE_URL is required" >&2
  exit 2
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --lat) LAT="$2"; shift 2 ;;
    --lng) LNG="$2"; shift 2 ;;
    --radius-m) RADIUS_M="$2"; shift 2 ;;
    --limit) LIMIT="$2"; shift 2 ;;
    --actor-id) ACTOR_ID="$2"; shift 2 ;;
    --out) OUT_DIR="$2"; shift 2 ;;
    *) usage ;;
  esac
done

[[ "$LAT" =~ ^-?[0-9]+([.][0-9]+)?$ ]] || usage
[[ "$LNG" =~ ^-?[0-9]+([.][0-9]+)?$ ]] || usage
[[ "$RADIUS_M" =~ ^[1-9][0-9]*$ ]] || usage
[[ "$LIMIT" =~ ^[1-9][0-9]*$ ]] || usage
[[ "$ACTOR_ID" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$ ]] || usage

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
OUT_DIR="${OUT_DIR:-performance-results/db-$timestamp}"
mkdir -p "$OUT_DIR"

psql_now -c "
  set statement_timeout = '10s';
  explain (analyze, buffers, format json)
  select *
  from app.query_nearby_states_v1(
    $LAT::double precision,
    $LNG::double precision,
    $RADIUS_M::integer,
    $LIMIT::integer,
    null,
    null
  );
" > "$OUT_DIR/nearby-states-plan.json"

psql_now -c "
  set statement_timeout = '10s';
  explain (analyze, buffers, format json)
  select *
  from app.query_nearby_opportunities_v1(
    '$ACTOR_ID'::uuid,
    $LAT::double precision,
    $LNG::double precision,
    $RADIUS_M::integer,
    $LIMIT::integer,
    null,
    null
  );
" > "$OUT_DIR/nearby-opportunities-plan.json"

cat > "$OUT_DIR/metadata.txt" <<EOF
recorded_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
git_sha=$(git rev-parse HEAD 2>/dev/null || echo unknown)
latitude=$LAT
longitude=$LNG
radius_m=$RADIUS_M
limit=$LIMIT
actor_id=$ACTOR_ID
database_mode=$([ -n "$DB_CONTAINER" ] && echo container || echo url)
EOF

echo "query plans written to: $OUT_DIR"
