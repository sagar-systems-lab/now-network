#!/usr/bin/env bash
set -Eeuo pipefail

DB_NAME="${DB_NAME:-now_test}"
DB_CONTAINER="${DB_CONTAINER:-}"
DATABASE_URL="${DATABASE_URL:-}"

usage() {
  cat >&2 <<'EOF'
usage:
  reconstruct-lifecycle.sh --refresh-id <uuid>
  reconstruct-lifecycle.sh --settlement-signature <base58-signature>

Database connection:
  DB_CONTAINER=<docker-container-id> [DB_NAME=now_test]
  or
  DATABASE_URL=<postgres-connection-string>
EOF
  exit 2
}

psql_now() {
  if [ -n "$DB_CONTAINER" ]; then
    docker exec -i "$DB_CONTAINER"       psql -XAtq -U postgres -d "$DB_NAME" -v ON_ERROR_STOP=1 "$@"
    return
  fi

  if [ -n "$DATABASE_URL" ]; then
    psql -XAtq "$DATABASE_URL" -v ON_ERROR_STOP=1 "$@"
    return
  fi

  echo "DB_CONTAINER or DATABASE_URL is required" >&2
  exit 2
}

REFRESH_ID=""
SETTLEMENT_SIGNATURE=""

while [ "$#" -gt 0 ]; do
  case "$1" in
    --refresh-id)
      [ "$#" -ge 2 ] || usage
      REFRESH_ID="$2"
      shift 2
      ;;
    --settlement-signature)
      [ "$#" -ge 2 ] || usage
      SETTLEMENT_SIGNATURE="$2"
      shift 2
      ;;
    *)
      usage
      ;;
  esac
done

if [ -n "$REFRESH_ID" ] && [ -n "$SETTLEMENT_SIGNATURE" ]; then
  usage
fi

if [ -z "$REFRESH_ID" ] && [ -z "$SETTLEMENT_SIGNATURE" ]; then
  usage
fi

if [ -n "$REFRESH_ID" ] && [[ ! "$REFRESH_ID" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$ ]]; then
  echo "refresh id is not a UUID" >&2
  exit 2
fi

if [ -n "$SETTLEMENT_SIGNATURE" ]; then
  if [[ ! "$SETTLEMENT_SIGNATURE" =~ ^[1-9A-HJ-NP-Za-km-z]{32,128}$ ]]; then
    echo "settlement signature is not valid base58-shaped text" >&2
    exit 2
  fi

  REFRESH_ID="$(
    psql_now -c "select refresh_id
      from app.settlement_operations
      where chain_signature = '$SETTLEMENT_SIGNATURE'
      limit 1;"
  )"

  if [ -z "$REFRESH_ID" ]; then
    echo "no settlement found for signature" >&2
    exit 3
  fi
fi

exists="$(
  psql_now -c "select count(*)
    from app.refresh_requests
    where refresh_id = '$REFRESH_ID'::uuid;"
)"

if [ "$exists" != "1" ]; then
  echo "refresh not found" >&2
  exit 3
fi

psql_now <<SQL
with
target_refresh as (
  select *
  from app.refresh_requests
  where refresh_id = '$REFRESH_ID'::uuid
),
related_entities as (
  select refresh_id as entity_id from target_refresh
  union
  select contribution_id from app.refresh_contributions where refresh_id = '$REFRESH_ID'::uuid
  union
  select acceptance_id from app.refresh_acceptances where refresh_id = '$REFRESH_ID'::uuid
  union
  select evidence_id from app.evidence_packets where refresh_id = '$REFRESH_ID'::uuid
  union
  select verification_result_id from app.verification_results where refresh_id = '$REFRESH_ID'::uuid
  union
  select settlement_id from app.settlement_operations where refresh_id = '$REFRESH_ID'::uuid
  union
  select receipt_id from app.receipts where refresh_id = '$REFRESH_ID'::uuid
),
related_events as (
  select d.*
  from app.domain_events d
  where d.entity_id in (select entity_id from related_entities)
     or d.payload ->> 'refresh_id' = '$REFRESH_ID'
),
refresh_json as (
  select jsonb_build_object(
    'refresh_id', rr.refresh_id,
    'state_id', rr.state_id,
    'status', rr.status,
    'revision', rr.revision,
    'verification_class', rr.verification_class,
    'required_witnesses', rr.required_witnesses,
    'max_witnesses', rr.max_witnesses,
    'reward_mint', rr.reward_mint,
    'chain_status', rr.chain_status,
    'chain_total_funded', rr.chain_total_funded::text,
    'chain_locked_reward', rr.chain_locked_reward::text,
    'created_at', rr.created_at,
    'updated_at', rr.updated_at,
    'evidence_deadline', rr.evidence_deadline,
    'refresh_expires_at', rr.refresh_expires_at
  ) as value
  from target_refresh rr
),
funding_json as (
  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'contribution_id', c.contribution_id,
        'operation_id', c.operation_id,
        'amount_atomic', c.amount_atomic::text,
        'status', c.status,
        'chain_signature', c.chain_signature,
        'chain_commitment', c.chain_commitment,
        'created_at', c.created_at,
        'updated_at', c.updated_at
      )
      order by c.created_at, c.contribution_id
    ),
    '[]'::jsonb
  ) as value
  from app.refresh_contributions c
  where c.refresh_id = '$REFRESH_ID'::uuid
),
claims_json as (
  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'acceptance_id', a.acceptance_id,
        'claim_slot', a.claim_slot,
        'status', a.status,
        'chain_signature', a.chain_signature,
        'chain_status', a.chain_status,
        'claim_deadline', a.claim_deadline,
        'revision', a.revision,
        'accepted_at', a.accepted_at,
        'released_at', a.released_at
      )
      order by a.accepted_at, a.acceptance_id
    ),
    '[]'::jsonb
  ) as value
  from app.refresh_acceptances a
  where a.refresh_id = '$REFRESH_ID'::uuid
),
evidence_json as (
  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'evidence_id', e.evidence_id,
        'acceptance_id', e.acceptance_id,
        'challenge_id', e.challenge_id,
        'status', e.status,
        'media_mime', e.media_mime,
        'media_size_bytes', e.media_size_bytes,
        'committed_at', e.committed_at,
        'revision', e.revision,
        'updated_at', e.updated_at
      )
      order by e.created_at, e.evidence_id
    ),
    '[]'::jsonb
  ) as value
  from app.evidence_packets e
  where e.refresh_id = '$REFRESH_ID'::uuid
),
verification_json as (
  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'verification_result_id', v.verification_result_id,
        'status', v.status,
        'reason_codes', v.reason_codes,
        'evidence_ids', v.evidence_ids,
        'verifier_build', v.verifier_build,
        'started_at', v.started_at,
        'completed_at', v.completed_at
      )
      order by v.created_at, v.verification_result_id
    ),
    '[]'::jsonb
  ) as value
  from app.verification_results v
  where v.refresh_id = '$REFRESH_ID'::uuid
),
settlement_json as (
  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'settlement_id', s.settlement_id,
        'verification_result_id', s.verification_result_id,
        'operation_id', s.operation_id,
        'status', s.status,
        'chain_signature', s.chain_signature,
        'chain_commitment', s.chain_commitment,
        'attempt_count', s.attempt_count,
        'last_error_code', s.last_error_code,
        'created_at', s.created_at,
        'updated_at', s.updated_at,
        'confirmed_at', s.confirmed_at,
        'finalized_at', s.finalized_at
      )
      order by s.created_at, s.settlement_id
    ),
    '[]'::jsonb
  ) as value
  from app.settlement_operations s
  where s.refresh_id = '$REFRESH_ID'::uuid
),
receipt_json as (
  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'receipt_id', r.receipt_id,
        'settlement_id', r.settlement_id,
        'verification_result_id', r.verification_result_id,
        'status', r.status,
        'reward_amount_atomic', r.reward_amount_atomic::text,
        'reward_mint', r.reward_mint,
        'settlement_signature', r.settlement_signature,
        'chain_commitment', r.chain_commitment,
        'revision', r.revision,
        'finalized_at', r.finalized_at
      )
      order by r.created_at, r.receipt_id
    ),
    '[]'::jsonb
  ) as value
  from app.receipts r
  where r.refresh_id = '$REFRESH_ID'::uuid
),
events_json as (
  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'sequence_no', e.sequence_no,
        'event_id', e.event_id,
        'entity_type', e.entity_type,
        'entity_id', e.entity_id,
        'event_type', e.event_type,
        'operation_id', e.operation_id,
        'entity_revision', e.entity_revision,
        'occurred_at', e.occurred_at
      )
      order by e.sequence_no
    ),
    '[]'::jsonb
  ) as value
  from related_events e
),
outbox_json as (
  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'outbox_id', o.outbox_id,
        'domain_event_id', o.domain_event_id,
        'event_type', o.event_type,
        'status', o.status,
        'attempt_count', o.attempt_count,
        'published_at', o.published_at,
        'last_error', o.last_error
      )
      order by o.created_at, o.outbox_id
    ),
    '[]'::jsonb
  ) as value
  from app.outbox_events o
  where o.domain_event_id in (select event_id from related_events)
),
realtime_json as (
  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'realtime_event_id', r.realtime_event_id,
        'source_outbox_id', r.source_outbox_id,
        'event_type', r.event_type,
        'entity_type', r.entity_type,
        'entity_id', r.entity_id,
        'entity_revision', r.entity_revision,
        'created_at', r.created_at,
        'expires_at', r.expires_at
      )
      order by r.created_at, r.realtime_event_id
    ),
    '[]'::jsonb
  ) as value
  from public.realtime_events_v1 r
  where r.source_outbox_id in (
    select o.outbox_id
    from app.outbox_events o
    where o.domain_event_id in (select event_id from related_events)
  )
)
select jsonb_pretty(
  jsonb_build_object(
    'refresh', (select value from refresh_json),
    'funding', (select value from funding_json),
    'claims', (select value from claims_json),
    'evidence', (select value from evidence_json),
    'verification', (select value from verification_json),
    'settlement', (select value from settlement_json),
    'receipt', (select value from receipt_json),
    'events', (select value from events_json),
    'outbox', (select value from outbox_json),
    'realtime', (select value from realtime_json)
  )
);
SQL
