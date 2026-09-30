#!/usr/bin/env bash
set -Eeuo pipefail

: "${DB_CONTAINER:?DB_CONTAINER is required}"

DB_NAME="${DB_NAME:-now_test}"

psql_root() {
  docker exec "$DB_CONTAINER" psql -XAtq -U postgres -d "$DB_NAME" -v ON_ERROR_STOP=1 "$@"
}

cleanup() {
  psql_root -c "
    delete from public.realtime_events_v1
    where realtime_event_id in (
      '91000000-0000-4000-8000-000000000001',
      '91000000-0000-4000-8000-000000000002',
      '91000000-0000-4000-8000-000000000003'
    );
  " >/dev/null 2>&1 || true
}
trap cleanup EXIT

for role in anon authenticated; do
  exists="$(psql_root -c "select count(*) from pg_roles where rolname = '$role';")"
  test "$exists" = "1"

  for relation in     app.actors     app.wallet_bindings     app.refresh_requests     app.refresh_acceptances     app.evidence_packets     app.verification_results     app.settlement_operations     app.receipts     app.security_events     app.runtime_health
  do
    direct="$(psql_root -c "select has_table_privilege('$role', '$relation', 'select,insert,update,delete');")"
    test "$direct" = "f"
  done

  public_read="$(psql_root -c "select has_table_privilege('$role', 'public.realtime_events_v1', 'select');")"
  public_write="$(psql_root -c "select has_table_privilege('$role', 'public.realtime_events_v1', 'insert,update,delete');")"
  test "$public_read" = "t"
  test "$public_write" = "f"
done

psql_root -c "
  begin;
  set local session_replication_role = replica;
  insert into public.realtime_events_v1(
    realtime_event_id,
    event_type,
    entity_type,
    entity_id,
    audience_type,
    audience_actor_id,
    payload,
    created_at,
    expires_at
  ) values
  (
    '91000000-0000-4000-8000-000000000001',
    'STATE_UPDATED',
    'STATE',
    '92000000-0000-4000-8000-000000000001',
    'PUBLIC_ENTITY',
    null,
    '{}'::jsonb,
    now(),
    now() + interval '5 minutes'
  ),
  (
    '91000000-0000-4000-8000-000000000002',
    'PRIVATE_TEST',
    'ACTOR',
    '92000000-0000-4000-8000-000000000002',
    'ACTOR_PRIVATE',
    '93000000-0000-4000-8000-000000000001',
    '{}'::jsonb,
    now(),
    now() + interval '5 minutes'
  ),
  (
    '91000000-0000-4000-8000-000000000003',
    'STATE_UPDATED',
    'STATE',
    '92000000-0000-4000-8000-000000000003',
    'PUBLIC_AREA',
    null,
    '{}'::jsonb,
    now() - interval '2 seconds',
    now() - interval '1 second'
  );
  commit;
"

for role in anon authenticated; do
  visible="$(
    psql_root -c "
      set role $role;
      select count(*)
      from public.realtime_events_v1
      where realtime_event_id in (
        '91000000-0000-4000-8000-000000000001',
        '91000000-0000-4000-8000-000000000002',
        '91000000-0000-4000-8000-000000000003'
      );
    "
  )"
  test "$visible" = "1"

  if psql_root -c "set role $role; select count(*) from app.actors;" >/dev/null 2>&1; then
    echo "$role unexpectedly read app.actors" >&2
    exit 1
  fi

  if psql_root -c "
    set role $role;
    insert into public.realtime_events_v1(
      realtime_event_id,
      event_type,
      entity_type,
      entity_id,
      audience_type,
      payload
    ) values (
      '94000000-0000-4000-8000-000000000001',
      'FORGED',
      'STATE',
      '95000000-0000-4000-8000-000000000001',
      'PUBLIC_ENTITY',
      '{}'::jsonb
    );
  " >/dev/null 2>&1; then
    echo "$role unexpectedly wrote realtime_events_v1" >&2
    exit 1
  fi
done

echo "database access attack surface: clean"
