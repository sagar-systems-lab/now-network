#!/usr/bin/env bash
set -Eeuo pipefail

: "${DB_CONTAINER:?DB_CONTAINER is required}"

DB_NAME="${DB_NAME:-now_test}"
OUTBOX_ID="d1000000-0000-4000-8000-000000000001"
ENTITY_ID="d2000000-0000-4000-8000-000000000001"

psql_root() {
  docker exec "$DB_CONTAINER" psql -XAtq -U postgres -d "$DB_NAME" -v ON_ERROR_STOP=1 "$@"
}

cleanup() {
  psql_root -c "
    begin;
    set local session_replication_role = replica;
    delete from public.realtime_events_v1
      where realtime_event_id = '$OUTBOX_ID'
         or source_outbox_id = '$OUTBOX_ID';
    delete from app.outbox_events
      where outbox_id = '$OUTBOX_ID';
    commit;
  " >/dev/null 2>&1 || true
}
trap cleanup EXIT
cleanup

psql_root -c "
  insert into app.outbox_events(
    outbox_id,
    domain_event_id,
    dedupe_key,
    event_type,
    entity_type,
    entity_id,
    entity_revision,
    audience_type,
    payload,
    status,
    next_attempt_at,
    created_at
  ) values (
    '$OUTBOX_ID',
    null,
    'recovery:$OUTBOX_ID',
    'STATE_UPDATED',
    'state',
    '$ENTITY_ID',
    1,
    'PUBLIC_ENTITY',
    '{}'::jsonb,
    'PENDING',
    now(),
    now()
  );
"

set +e
docker exec "$DB_CONTAINER" psql -X -U postgres -d "$DB_NAME" -v ON_ERROR_STOP=1 >/dev/null 2>&1 <<SQL
begin;

update app.outbox_events
set
  status = 'PUBLISHING',
  attempt_count = attempt_count + 1,
  last_error = null
where outbox_id = '$OUTBOX_ID';

insert into public.realtime_events_v1(
  realtime_event_id,
  source_outbox_id,
  event_type,
  entity_type,
  entity_id,
  entity_revision,
  audience_type,
  payload,
  created_at
)
select
  outbox_id,
  outbox_id,
  event_type,
  entity_type,
  entity_id,
  entity_revision,
  audience_type,
  payload,
  created_at
from app.outbox_events
where outbox_id = '$OUTBOX_ID';

select 1 / 0;

update app.outbox_events
set status = 'PUBLISHED'
where outbox_id = '$OUTBOX_ID';

commit;
SQL
crash_rc=$?
set -e

if [ "$crash_rc" -eq 0 ]; then
  echo "fault injection unexpectedly committed" >&2
  exit 1
fi

status_after_crash="$(psql_root -c "
  select status || ':' || attempt_count
  from app.outbox_events
  where outbox_id = '$OUTBOX_ID';
")"
test "$status_after_crash" = "PENDING:0"

events_after_crash="$(psql_root -c "
  select count(*)
  from public.realtime_events_v1
  where source_outbox_id = '$OUTBOX_ID';
")"
test "$events_after_crash" = "0"

psql_root -c "
  begin;

  update app.outbox_events
  set
    status = 'PUBLISHING',
    attempt_count = attempt_count + 1,
    last_error = null
  where outbox_id = '$OUTBOX_ID';

  insert into public.realtime_events_v1(
    realtime_event_id,
    source_outbox_id,
    event_type,
    entity_type,
    entity_id,
    entity_revision,
    audience_type,
    payload,
    created_at
  )
  select
    outbox_id,
    outbox_id,
    event_type,
    entity_type,
    entity_id,
    entity_revision,
    audience_type,
    payload,
    created_at
  from app.outbox_events
  where outbox_id = '$OUTBOX_ID'
  on conflict (source_outbox_id) do nothing;

  update app.outbox_events
  set
    status = 'PUBLISHED',
    next_attempt_at = null,
    published_at = now(),
    last_error = null
  where outbox_id = '$OUTBOX_ID';

  commit;
"

psql_root -c "
  insert into public.realtime_events_v1(
    realtime_event_id,
    source_outbox_id,
    event_type,
    entity_type,
    entity_id,
    entity_revision,
    audience_type,
    payload,
    created_at
  )
  select
    gen_random_uuid(),
    outbox_id,
    event_type,
    entity_type,
    entity_id,
    entity_revision,
    audience_type,
    payload,
    created_at
  from app.outbox_events
  where outbox_id = '$OUTBOX_ID'
  on conflict (source_outbox_id) do nothing;
"

final_state="$(psql_root -c "
  select status || ':' || attempt_count
  from app.outbox_events
  where outbox_id = '$OUTBOX_ID';
")"
test "$final_state" = "PUBLISHED:1"

final_events="$(psql_root -c "
  select count(*)
  from public.realtime_events_v1
  where source_outbox_id = '$OUTBOX_ID';
")"
test "$final_events" = "1"

echo "transaction recovery invariants: clean"
