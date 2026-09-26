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

rollback;
