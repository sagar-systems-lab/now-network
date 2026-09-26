\set ON_ERROR_STOP on

begin;

do $$
declare
  trigger_count integer;
  receipt_trigger_count integer;
  public_policy_count integer;
  receipt_index text;
  outbox_index text;
  realtime_index text;
begin
  select count(*)
  into trigger_count
  from pg_trigger
  where tgrelid = 'app.domain_events'::regclass
    and tgname = 'domain_events_realtime_outbox'
    and not tgisinternal;

  if trigger_count <> 1 then
    raise exception 'realtime outbox trigger missing';
  end if;

  select count(*)
  into receipt_trigger_count
  from pg_trigger
  where tgrelid = 'app.receipts'::regclass
    and tgname = 'receipts_final_immutable'
    and not tgisinternal;

  if receipt_trigger_count <> 1 then
    raise exception 'final receipt immutability trigger missing';
  end if;

  select count(*)
  into public_policy_count
  from pg_policies
  where schemaname = 'public'
    and tablename = 'realtime_events_v1'
    and policyname = 'realtime_public_read_v1';

  if public_policy_count <> 1 then
    raise exception 'public realtime read policy missing';
  end if;

  select indexdef
  into receipt_index
  from pg_indexes
  where schemaname = 'app'
    and tablename = 'receipts'
    and indexname = 'receipts_settlement_uq';

  if receipt_index is null
     or position('UNIQUE INDEX' in receipt_index) = 0 then
    raise exception 'receipt settlement uniqueness missing';
  end if;

  select indexdef
  into outbox_index
  from pg_indexes
  where schemaname = 'app'
    and tablename = 'outbox_events'
    and indexname = 'outbox_events_dedupe_key_uq';

  if outbox_index is null
     or position('UNIQUE INDEX' in outbox_index) = 0 then
    raise exception 'outbox dedupe uniqueness missing';
  end if;

  select indexdef
  into realtime_index
  from pg_indexes
  where schemaname = 'public'
    and tablename = 'realtime_events_v1'
    and indexname = 'realtime_events_source_outbox_uq';

  if realtime_index is null
     or position('UNIQUE INDEX' in realtime_index) = 0 then
    raise exception 'realtime source outbox uniqueness missing';
  end if;
end
$$;

insert into app.domain_events(
  event_id,
  entity_type,
  entity_id,
  event_type,
  entity_revision,
  payload,
  occurred_at
) values (
  'd1000000-0000-4000-8000-000000000001',
  'state',
  'd2000000-0000-4000-8000-000000000001',
  'STATE_PROJECTED',
  4,
  '{"locked_reward_atomic":"should-not-leak"}'::jsonb,
  '2026-09-26T06:00:00Z'
);

do $$
declare
  queued_count integer;
  queued_type text;
  queued_payload jsonb;
begin
  select count(*), max(event_type), max(payload::text)::jsonb
  into queued_count, queued_type, queued_payload
  from app.outbox_events
  where domain_event_id = 'd1000000-0000-4000-8000-000000000001'::uuid;

  if queued_count <> 1 or queued_type <> 'STATE_UPDATED' then
    raise exception 'state projection was not enqueued exactly once';
  end if;

  if queued_payload ? 'locked_reward_atomic' then
    raise exception 'domain payload leaked into public realtime outbox';
  end if;
end
$$;

insert into app.domain_events(
  event_id,
  entity_type,
  entity_id,
  event_type,
  entity_revision,
  payload,
  occurred_at
) values (
  'd1000000-0000-4000-8000-000000000003',
  'receipt',
  'd2000000-0000-4000-8000-000000000003',
  'RECEIPT_FINALIZED',
  1,
  '{
    "receipt_id":"d2000000-0000-4000-8000-000000000003",
    "refresh_id":"d2000000-0000-4000-8000-000000000004",
    "reward_amount_atomic":"should-not-leak"
  }'::jsonb,
  '2026-09-26T06:00:00.500Z'
);

do $$
declare
  receipt_type text;
  receipt_entity_type text;
  receipt_entity_id uuid;
  receipt_payload jsonb;
begin
  select event_type, entity_type, entity_id, payload
  into receipt_type, receipt_entity_type, receipt_entity_id, receipt_payload
  from app.outbox_events
  where domain_event_id = 'd1000000-0000-4000-8000-000000000003'::uuid;

  if receipt_type <> 'RECEIPT_FINALIZED'
     or receipt_entity_type <> 'receipt'
     or receipt_entity_id <> 'd2000000-0000-4000-8000-000000000003'::uuid then
    raise exception 'receipt realtime identity was not preserved';
  end if;

  if receipt_payload ->> 'refresh_id' <>
      'd2000000-0000-4000-8000-000000000004' then
    raise exception 'receipt realtime payload lost refresh identity';
  end if;

  if receipt_payload ? 'reward_amount_atomic' then
    raise exception 'receipt financial data leaked into public realtime outbox';
  end if;
end
$$;

insert into app.domain_events(
  event_id,
  entity_type,
  entity_id,
  event_type,
  payload,
  occurred_at
) values (
  'd1000000-0000-4000-8000-000000000002',
  'settlement',
  'd2000000-0000-4000-8000-000000000002',
  'SETTLEMENT_ATTEMPT_RESERVED',
  '{}'::jsonb,
  '2026-09-26T06:00:01Z'
);

do $$
declare
  ignored_count integer;
begin
  select count(*)
  into ignored_count
  from app.outbox_events
  where domain_event_id = 'd1000000-0000-4000-8000-000000000002'::uuid;

  if ignored_count <> 0 then
    raise exception 'non-realtime domain event entered public outbox';
  end if;
end
$$;


set local session_replication_role = replica;

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
  finalized_at,
  revision,
  settlement_operation_hash
) values (
  'd3000000-0000-4000-8000-000000000001',
  'd3000000-0000-4000-8000-000000000002',
  'd3000000-0000-4000-8000-000000000003',
  'd3000000-0000-4000-8000-000000000004',
  'd3000000-0000-4000-8000-000000000005',
  'FINAL',
  '{"kind":"numeric","scaled_value":"2","scale":0}'::jsonb,
  '2026-09-26T06:00:00Z',
  'FAST',
  1,
  'So11111111111111111111111111111111111111112',
  decode(repeat('55', 32), 'hex'),
  'final-signature',
  'finalized',
  decode(repeat('66', 32), 'hex'),
  '2026-09-26T06:01:00Z',
  1,
  decode(repeat('77', 32), 'hex')
);

set local session_replication_role = origin;

do $$
begin
  begin
    update app.receipts
    set reward_amount_atomic = 2
    where receipt_id = 'd3000000-0000-4000-8000-000000000001'::uuid;

    raise exception 'final receipt mutation was accepted';
  exception
    when raise_exception then
      if sqlerrm = 'final receipt mutation was accepted' then
        raise;
      end if;
  end;
end
$$;

rollback;
