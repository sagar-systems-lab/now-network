#!/usr/bin/env bash
set -Eeuo pipefail

: "${DB_CONTAINER:?DB_CONTAINER is required}"

DB_NAME="${DB_NAME:-now_test}"
TMP="$(mktemp -d)"

cleanup() {
  docker exec "$DB_CONTAINER" psql -XAtq -U postgres -d "$DB_NAME" -v ON_ERROR_STOP=1 -c "
    begin;
    set local session_replication_role = replica;
    delete from app.refresh_acceptances
      where acceptance_id in (
        'a1000000-0000-4000-8000-000000000001',
        'a1000000-0000-4000-8000-000000000002'
      );
    delete from app.settlement_operations
      where settlement_id in (
        'b1000000-0000-4000-8000-000000000001',
        'b1000000-0000-4000-8000-000000000002'
      );
    delete from app.evidence_packets
      where evidence_id in (
        'c1000000-0000-4000-8000-000000000001',
        'c1000000-0000-4000-8000-000000000002'
      );
    commit;
  " >/dev/null 2>&1 || true
  rm -rf "$TMP"
}
cleanup
TMP="$(mktemp -d)"
trap cleanup EXIT

psql_sql() {
  local sql="$1"
  docker exec "$DB_CONTAINER" psql -X -U postgres -d "$DB_NAME" -v ON_ERROR_STOP=1 -c "$sql"
}

assert_single_winner() {
  local name="$1"
  local left_sql="$2"
  local right_sql="$3"

  set +e
  psql_sql "$left_sql" >"$TMP/$name-left.log" 2>&1 &
  local left_pid=$!
  psql_sql "$right_sql" >"$TMP/$name-right.log" 2>&1 &
  local right_pid=$!

  wait "$left_pid"
  local left_rc=$?
  wait "$right_pid"
  local right_rc=$?
  set -e

  local wins=0
  [ "$left_rc" -eq 0 ] && wins=$((wins + 1))
  [ "$right_rc" -eq 0 ] && wins=$((wins + 1))

  if [ "$wins" -ne 1 ]; then
    echo "$name expected exactly one successful concurrent transaction" >&2
    echo "--- left ---" >&2
    cat "$TMP/$name-left.log" >&2
    echo "--- right ---" >&2
    cat "$TMP/$name-right.log" >&2
    exit 1
  fi
}

CLAIM_LEFT="
  begin;
  set local session_replication_role = replica;
  insert into app.refresh_acceptances(
    acceptance_id, refresh_id, actor_id, wallet_address,
    claim_slot, status, accepted_at
  ) values (
    'a1000000-0000-4000-8000-000000000001',
    'a2000000-0000-4000-8000-000000000001',
    'a3000000-0000-4000-8000-000000000001',
    'wallet-left',
    0,
    'CLAIMED',
    now()
  );
  select pg_sleep(0.25);
  commit;
"

CLAIM_RIGHT="
  begin;
  set local session_replication_role = replica;
  insert into app.refresh_acceptances(
    acceptance_id, refresh_id, actor_id, wallet_address,
    claim_slot, status, accepted_at
  ) values (
    'a1000000-0000-4000-8000-000000000002',
    'a2000000-0000-4000-8000-000000000001',
    'a3000000-0000-4000-8000-000000000002',
    'wallet-right',
    0,
    'CLAIMED',
    now()
  );
  select pg_sleep(0.25);
  commit;
"

assert_single_winner "claim-slot-race" "$CLAIM_LEFT" "$CLAIM_RIGHT"

claim_count="$(
  docker exec "$DB_CONTAINER" psql -XAtq -U postgres -d "$DB_NAME" -c "
    select count(*)
    from app.refresh_acceptances
    where refresh_id = 'a2000000-0000-4000-8000-000000000001'
      and claim_slot = 0
      and status = 'CLAIMED';
  "
)"
test "$claim_count" = "1"

SETTLEMENT_LEFT="
  begin;
  set local session_replication_role = replica;
  insert into app.settlement_operations(
    settlement_id, refresh_id, verification_result_id, operation_id,
    operation_hash, verification_digest, execution_hash,
    recipient_mask, recipient_wallets, chain_refresh_id,
    refresh_expires_at, reward_mint, locked_reward_atomic,
    chain_refresh_address, status
  ) values (
    'b1000000-0000-4000-8000-000000000001',
    'b2000000-0000-4000-8000-000000000001',
    'b3000000-0000-4000-8000-000000000001',
    'b4000000-0000-4000-8000-000000000001',
    decode(repeat('11', 32), 'hex'),
    decode(repeat('21', 32), 'hex'),
    decode(repeat('31', 32), 'hex'),
    1,
    array['wallet-left'],
    decode(repeat('41', 32), 'hex'),
    now() + interval '10 minutes',
    'mint-test',
    1000,
    'refresh-address',
    'ELIGIBLE'
  );
  select pg_sleep(0.25);
  commit;
"

SETTLEMENT_RIGHT="
  begin;
  set local session_replication_role = replica;
  insert into app.settlement_operations(
    settlement_id, refresh_id, verification_result_id, operation_id,
    operation_hash, verification_digest, execution_hash,
    recipient_mask, recipient_wallets, chain_refresh_id,
    refresh_expires_at, reward_mint, locked_reward_atomic,
    chain_refresh_address, status
  ) values (
    'b1000000-0000-4000-8000-000000000002',
    'b2000000-0000-4000-8000-000000000001',
    'b3000000-0000-4000-8000-000000000002',
    'b4000000-0000-4000-8000-000000000002',
    decode(repeat('12', 32), 'hex'),
    decode(repeat('22', 32), 'hex'),
    decode(repeat('32', 32), 'hex'),
    1,
    array['wallet-right'],
    decode(repeat('42', 32), 'hex'),
    now() + interval '10 minutes',
    'mint-test',
    1000,
    'refresh-address',
    'ELIGIBLE'
  );
  select pg_sleep(0.25);
  commit;
"

assert_single_winner "settlement-race" "$SETTLEMENT_LEFT" "$SETTLEMENT_RIGHT"

settlement_count="$(
  docker exec "$DB_CONTAINER" psql -XAtq -U postgres -d "$DB_NAME" -c "
    select count(*)
    from app.settlement_operations
    where refresh_id = 'b2000000-0000-4000-8000-000000000001';
  "
)"
test "$settlement_count" = "1"

EVIDENCE_LEFT="
  begin;
  set local session_replication_role = replica;
  insert into app.evidence_packets(
    evidence_id, refresh_id, acceptance_id, challenge_id, actor_id,
    wallet_address, state_id, state_version, intent_core_hash,
    execution_hash, answer_type, answer_value, status, commit_request_hash
  ) values (
    'c1000000-0000-4000-8000-000000000001',
    'c2000000-0000-4000-8000-000000000001',
    'c3000000-0000-4000-8000-000000000001',
    'c4000000-0000-4000-8000-000000000001',
    'c5000000-0000-4000-8000-000000000001',
    'wallet-evidence',
    'c6000000-0000-4000-8000-000000000001',
    1,
    decode(repeat('51', 32), 'hex'),
    decode(repeat('61', 32), 'hex'),
    'NUMERIC',
    '1'::jsonb,
    'COMMITTED',
    decode(repeat('71', 32), 'hex')
  );
  select pg_sleep(0.25);
  commit;
"

EVIDENCE_RIGHT="
  begin;
  set local session_replication_role = replica;
  insert into app.evidence_packets(
    evidence_id, refresh_id, acceptance_id, challenge_id, actor_id,
    wallet_address, state_id, state_version, intent_core_hash,
    execution_hash, answer_type, answer_value, status, commit_request_hash
  ) values (
    'c1000000-0000-4000-8000-000000000002',
    'c2000000-0000-4000-8000-000000000002',
    'c3000000-0000-4000-8000-000000000002',
    'c4000000-0000-4000-8000-000000000002',
    'c5000000-0000-4000-8000-000000000001',
    'wallet-evidence',
    'c6000000-0000-4000-8000-000000000002',
    1,
    decode(repeat('52', 32), 'hex'),
    decode(repeat('62', 32), 'hex'),
    'NUMERIC',
    '2'::jsonb,
    'COMMITTED',
    decode(repeat('71', 32), 'hex')
  );
  select pg_sleep(0.25);
  commit;
"

assert_single_winner "evidence-commit-race" "$EVIDENCE_LEFT" "$EVIDENCE_RIGHT"

evidence_count="$(
  docker exec "$DB_CONTAINER" psql -XAtq -U postgres -d "$DB_NAME" -c "
    select count(*)
    from app.evidence_packets
    where actor_id = 'c5000000-0000-4000-8000-000000000001'
      and commit_request_hash = decode(repeat('71', 32), 'hex');
  "
)"
test "$evidence_count" = "1"

echo "database race invariants: clean"
