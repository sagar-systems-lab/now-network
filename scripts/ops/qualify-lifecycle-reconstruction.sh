#!/usr/bin/env bash
set -Eeuo pipefail

: "${DB_CONTAINER:?DB_CONTAINER is required}"

DB_NAME="${DB_NAME:-now_test}"

REFRESH_ID="f1000000-0000-4000-8000-000000000001"
STATE_ID="f2000000-0000-4000-8000-000000000001"
CONTRIBUTION_ID="f3000000-0000-4000-8000-000000000001"
ACCEPTANCE_ID="f4000000-0000-4000-8000-000000000001"
EVIDENCE_ID="f5000000-0000-4000-8000-000000000001"
CHALLENGE_ID="f6000000-0000-4000-8000-000000000001"
VERIFICATION_ID="f7000000-0000-4000-8000-000000000001"
SETTLEMENT_ID="f8000000-0000-4000-8000-000000000001"
RECEIPT_ID="f9000000-0000-4000-8000-000000000001"
REQUESTER_ACTOR="fa000000-0000-4000-8000-000000000001"
CONTRIBUTOR_ACTOR="fb000000-0000-4000-8000-000000000001"
FUNDING_OPERATION="fc000000-0000-4000-8000-000000000001"
SETTLEMENT_OPERATION="fd000000-0000-4000-8000-000000000001"
EVENT_REFRESH="e1000000-0000-4000-8000-000000000001"
EVENT_SETTLEMENT="e1000000-0000-4000-8000-000000000002"
EVENT_RECEIPT="e1000000-0000-4000-8000-000000000003"
OUTBOX_ID="e2000000-0000-4000-8000-000000000001"
REALTIME_ID="e3000000-0000-4000-8000-000000000001"
SETTLEMENT_SIGNATURE="$(printf '3%.0s' {1..64})"
FUNDING_SIGNATURE="$(printf '4%.0s' {1..64})"
CLAIM_SIGNATURE="$(printf '5%.0s' {1..64})"

psql_root() {
  docker exec "$DB_CONTAINER"     psql -XAtq -U postgres -d "$DB_NAME" -v ON_ERROR_STOP=1 "$@"
}

cleanup() {
  psql_root -c "
    begin;
    set local session_replication_role = replica;
    delete from public.realtime_events_v1 where realtime_event_id = '$REALTIME_ID';
    delete from app.outbox_events where outbox_id = '$OUTBOX_ID';
    delete from app.domain_events where event_id in ('$EVENT_REFRESH', '$EVENT_SETTLEMENT', '$EVENT_RECEIPT');
    delete from app.receipts where receipt_id = '$RECEIPT_ID';
    delete from app.settlement_operations where settlement_id = '$SETTLEMENT_ID';
    delete from app.verification_results where verification_result_id = '$VERIFICATION_ID';
    delete from app.evidence_packets where evidence_id = '$EVIDENCE_ID';
    delete from app.refresh_acceptances where acceptance_id = '$ACCEPTANCE_ID';
    delete from app.refresh_contributions where contribution_id = '$CONTRIBUTION_ID';
    delete from app.refresh_requests where refresh_id = '$REFRESH_ID';
    commit;
  " >/dev/null 2>&1 || true
}
trap cleanup EXIT
cleanup

psql_root -c "
  begin;
  set local session_replication_role = replica;

  insert into app.refresh_requests(
    refresh_id,
    state_id,
    state_version,
    requester_actor_id,
    status,
    verification_class,
    required_witnesses,
    max_witnesses,
    proof_policy_snapshot,
    proof_policy_digest,
    intent_core_hash,
    execution_hash,
    refresh_expires_at,
    evidence_deadline,
    reward_mint,
    chain_status,
    chain_total_funded,
    chain_locked_reward,
    created_at,
    updated_at,
    revision
  ) values (
    '$REFRESH_ID',
    '$STATE_ID',
    1,
    '$REQUESTER_ACTOR',
    'COMPLETED',
    'FAST',
    1,
    1,
    '{}'::jsonb,
    decode(repeat('11', 32), 'hex'),
    decode(repeat('22', 32), 'hex'),
    decode(repeat('33', 32), 'hex'),
    now() + interval '10 minutes',
    now() + interval '5 minutes',
    'mint-test',
    'SETTLED_FINALIZED',
    1000,
    1000,
    now(),
    now(),
    9
  );

  insert into app.refresh_contributions(
    contribution_id,
    refresh_id,
    actor_id,
    wallet_address,
    operation_id,
    amount_atomic,
    chain_signature,
    chain_commitment,
    status,
    created_at,
    updated_at
  ) values (
    '$CONTRIBUTION_ID',
    '$REFRESH_ID',
    '$REQUESTER_ACTOR',
    'wallet-requester',
    '$FUNDING_OPERATION',
    1000,
    '$FUNDING_SIGNATURE',
    'finalized',
    'FINALIZED',
    now(),
    now()
  );

  insert into app.refresh_acceptances(
    acceptance_id,
    refresh_id,
    actor_id,
    wallet_address,
    claim_slot,
    claim_deadline,
    chain_signature,
    chain_status,
    status,
    accepted_at,
    released_at,
    revision
  ) values (
    '$ACCEPTANCE_ID',
    '$REFRESH_ID',
    '$CONTRIBUTOR_ACTOR',
    'wallet-contributor',
    0,
    now() + interval '4 minutes',
    '$CLAIM_SIGNATURE',
    'finalized',
    'RELEASED',
    now(),
    now() + interval '1 minute',
    5
  );

  insert into app.evidence_packets(
    evidence_id,
    refresh_id,
    acceptance_id,
    challenge_id,
    actor_id,
    wallet_address,
    state_id,
    state_version,
    intent_core_hash,
    execution_hash,
    answer_type,
    answer_value,
    media_sha256,
    media_size_bytes,
    media_mime,
    status,
    created_at,
    committed_at,
    updated_at,
    revision,
    commit_request_hash
  ) values (
    '$EVIDENCE_ID',
    '$REFRESH_ID',
    '$ACCEPTANCE_ID',
    '$CHALLENGE_ID',
    '$CONTRIBUTOR_ACTOR',
    'wallet-contributor',
    '$STATE_ID',
    1,
    decode(repeat('22', 32), 'hex'),
    decode(repeat('33', 32), 'hex'),
    'NUMERIC',
    '2'::jsonb,
    decode(repeat('44', 32), 'hex'),
    123,
    'image/jpeg',
    'VERIFIED',
    now(),
    now(),
    now(),
    3,
    decode(repeat('45', 32), 'hex')
  );

  insert into app.verification_results(
    verification_result_id,
    refresh_id,
    evidence_set_revision,
    policy_version,
    status,
    reason_codes,
    evidence_ids,
    final_answer,
    verification_trace,
    execution_hash,
    canonical_digest,
    verifier_build,
    started_at,
    completed_at,
    created_at
  ) values (
    '$VERIFICATION_ID',
    '$REFRESH_ID',
    1,
    1,
    'VERIFIED',
    '{}'::text[],
    array['$EVIDENCE_ID'::uuid],
    '2'::jsonb,
    '{}'::jsonb,
    decode(repeat('33', 32), 'hex'),
    decode(repeat('55', 32), 'hex'),
    'qualification-build',
    now(),
    now() + interval '1 second',
    now()
  );

  insert into app.settlement_operations(
    settlement_id,
    refresh_id,
    verification_result_id,
    operation_id,
    operation_hash,
    verification_digest,
    status,
    chain_signature,
    recent_blockhash,
    last_valid_block_height,
    chain_commitment,
    attempt_count,
    created_at,
    updated_at,
    confirmed_at,
    finalized_at,
    execution_hash,
    recipient_mask,
    recipient_wallets,
    chain_refresh_id,
    refresh_expires_at,
    reward_mint,
    locked_reward_atomic,
    chain_refresh_address,
    signed_transaction_base64
  ) values (
    '$SETTLEMENT_ID',
    '$REFRESH_ID',
    '$VERIFICATION_ID',
    '$SETTLEMENT_OPERATION',
    decode(repeat('66', 32), 'hex'),
    decode(repeat('55', 32), 'hex'),
    'FINALIZED',
    '$SETTLEMENT_SIGNATURE',
    'qualification-blockhash',
    999,
    'finalized',
    1,
    now(),
    now(),
    now() + interval '2 seconds',
    now() + interval '3 seconds',
    decode(repeat('33', 32), 'hex'),
    1,
    array['wallet-contributor'],
    decode(repeat('77', 32), 'hex'),
    now() + interval '10 minutes',
    'mint-test',
    1000,
    'refresh-chain-test',
    'c2lnbmVkLXRyYW5zYWN0aW9u'
  );

  insert into app.receipts(
    receipt_id,
    refresh_id,
    state_id,
    verification_result_id,
    settlement_id,
    status,
    final_value,
    observed_at,
    verification_class,
    reward_amount_atomic,
    reward_mint,
    verification_digest,
    settlement_signature,
    chain_commitment,
    receipt_digest,
    created_at,
    updated_at,
    finalized_at,
    revision,
    settlement_operation_hash
  ) values (
    '$RECEIPT_ID',
    '$REFRESH_ID',
    '$STATE_ID',
    '$VERIFICATION_ID',
    '$SETTLEMENT_ID',
    'FINAL',
    '2'::jsonb,
    now(),
    'FAST',
    1000,
    'mint-test',
    decode(repeat('55', 32), 'hex'),
    '$SETTLEMENT_SIGNATURE',
    'finalized',
    decode(repeat('88', 32), 'hex'),
    now(),
    now(),
    now() + interval '4 seconds',
    1,
    decode(repeat('66', 32), 'hex')
  );

  insert into app.domain_events(
    event_id,
    entity_type,
    entity_id,
    event_type,
    operation_id,
    entity_revision,
    payload,
    occurred_at
  ) values
  (
    '$EVENT_REFRESH',
    'refresh',
    '$REFRESH_ID',
    'REFRESH_AVAILABLE',
    '$FUNDING_OPERATION',
    4,
    jsonb_build_object('refresh_id', '$REFRESH_ID'),
    now()
  ),
  (
    '$EVENT_SETTLEMENT',
    'settlement',
    '$SETTLEMENT_ID',
    'SETTLEMENT_FINALIZED',
    '$SETTLEMENT_OPERATION',
    1,
    jsonb_build_object(
      'refresh_id', '$REFRESH_ID',
      'chain_signature', '$SETTLEMENT_SIGNATURE'
    ),
    now() + interval '3 seconds'
  ),
  (
    '$EVENT_RECEIPT',
    'receipt',
    '$RECEIPT_ID',
    'RECEIPT_FINALIZED',
    '$SETTLEMENT_OPERATION',
    1,
    jsonb_build_object('refresh_id', '$REFRESH_ID'),
    now() + interval '4 seconds'
  );

  insert into app.outbox_events(
    outbox_id,
    domain_event_id,
    event_type,
    payload,
    status,
    attempt_count,
    created_at,
    published_at,
    dedupe_key,
    entity_type,
    entity_id,
    entity_revision,
    audience_type
  ) values (
    '$OUTBOX_ID',
    '$EVENT_RECEIPT',
    'RECEIPT_FINALIZED',
    jsonb_build_object('refresh_id', '$REFRESH_ID'),
    'PUBLISHED',
    1,
    now() + interval '4 seconds',
    now() + interval '4 seconds',
    'qualification:$EVENT_RECEIPT',
    'receipt',
    '$RECEIPT_ID',
    1,
    'PUBLIC_ENTITY'
  );

  insert into public.realtime_events_v1(
    realtime_event_id,
    event_type,
    entity_type,
    entity_id,
    entity_revision,
    audience_type,
    payload,
    created_at,
    source_outbox_id
  ) values (
    '$REALTIME_ID',
    'RECEIPT_FINALIZED',
    'receipt',
    '$RECEIPT_ID',
    1,
    'PUBLIC_ENTITY',
    jsonb_build_object('refresh_id', '$REFRESH_ID'),
    now() + interval '4 seconds',
    '$OUTBOX_ID'
  );

  commit;
"

by_refresh="$(
  DB_CONTAINER="$DB_CONTAINER" DB_NAME="$DB_NAME"     bash scripts/ops/reconstruct-lifecycle.sh --refresh-id "$REFRESH_ID"
)"

for expected in   "$REFRESH_ID"   "$CONTRIBUTION_ID"   "$ACCEPTANCE_ID"   "$EVIDENCE_ID"   "$VERIFICATION_ID"   "$SETTLEMENT_ID"   "$RECEIPT_ID"   "$SETTLEMENT_SIGNATURE"   "SETTLEMENT_FINALIZED"   "RECEIPT_FINALIZED"   '"status": "PUBLISHED"'
do
  grep -Fq "$expected" <<<"$by_refresh"
done

if grep -Fq "wallet-contributor" <<<"$by_refresh"; then
  echo "reconstruction output leaked wallet address" >&2
  exit 1
fi

by_signature="$(
  DB_CONTAINER="$DB_CONTAINER" DB_NAME="$DB_NAME"     bash scripts/ops/reconstruct-lifecycle.sh       --settlement-signature "$SETTLEMENT_SIGNATURE"
)"

grep -Fq "$REFRESH_ID" <<<"$by_signature"
grep -Fq "$RECEIPT_ID" <<<"$by_signature"
grep -Fq "$SETTLEMENT_SIGNATURE" <<<"$by_signature"

echo "lifecycle reconstruction: clean"
