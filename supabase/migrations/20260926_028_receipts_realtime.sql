alter table app.receipts
  add column settlement_operation_hash bytea;

update app.receipts r
set settlement_operation_hash = so.operation_hash
from app.settlement_operations so
where so.settlement_id = r.settlement_id;

do $$
begin
  if exists (
    select 1
    from app.receipts
    where settlement_operation_hash is null
  ) then
    raise exception 'existing receipt cannot be bound to settlement operation';
  end if;
end
$$;

alter table app.receipts
  alter column settlement_operation_hash set not null,
  add constraint receipts_verification_digest_32_ck
    check (octet_length(verification_digest) = 32),
  add constraint receipts_operation_hash_32_ck
    check (octet_length(settlement_operation_hash) = 32),
  add constraint receipts_receipt_digest_32_ck
    check (octet_length(receipt_digest) = 32),
  add constraint receipts_final_shape_ck
    check (
      status not in ('FINAL', 'ANNOTATED')
      or (
        settlement_signature is not null
        and length(btrim(settlement_signature)) > 0
        and chain_commitment = 'finalized'
        and finalized_at is not null
      )
    );

create unique index receipts_settlement_uq
  on app.receipts(settlement_id);

create unique index receipts_verification_result_uq
  on app.receipts(verification_result_id);

create or replace function app.reject_final_receipt_mutation()
returns trigger
language plpgsql
as $$
begin
  if old.status in ('FINAL', 'ANNOTATED') then
    raise exception 'final receipt is immutable';
  end if;
  if tg_op = 'DELETE' then
    return old;
  end if;
  return new;
end;
$$;

create trigger receipts_final_immutable
before update or delete on app.receipts
for each row execute function app.reject_final_receipt_mutation();

alter table app.outbox_events
  add column dedupe_key text,
  add column entity_type text,
  add column entity_id uuid,
  add column entity_revision bigint,
  add column audience_type app.event_visibility,
  add column audience_actor_id uuid references app.actors(actor_id) on delete restrict,
  add column area_key text,
  add column expires_at timestamptz;

update app.outbox_events o
set
  dedupe_key = 'domain:' || o.domain_event_id::text || ':PUBLIC_ENTITY',
  entity_type = d.entity_type,
  entity_id = d.entity_id,
  entity_revision = d.entity_revision,
  audience_type = 'PUBLIC_ENTITY'
from app.domain_events d
where o.domain_event_id = d.event_id
  and o.dedupe_key is null;

do $$
begin
  if exists (
    select 1
    from app.outbox_events
    where dedupe_key is null
       or entity_type is null
       or entity_id is null
       or audience_type is null
  ) then
    raise exception 'existing outbox event cannot be upgraded safely';
  end if;
end
$$;

alter table app.outbox_events
  alter column dedupe_key set not null,
  alter column entity_type set not null,
  alter column entity_id set not null,
  alter column audience_type set not null,
  add constraint outbox_dedupe_key_nonempty_ck
    check (length(btrim(dedupe_key)) > 0),
  add constraint outbox_entity_type_nonempty_ck
    check (length(btrim(entity_type)) > 0),
  add constraint outbox_revision_ck
    check (entity_revision is null or entity_revision > 0),
  add constraint outbox_audience_shape_ck
    check (
      audience_type <> 'ACTOR_PRIVATE'
      or audience_actor_id is not null
    ),
  add constraint outbox_expiry_ck
    check (expires_at is null or expires_at > created_at);

create unique index outbox_events_dedupe_key_uq
  on app.outbox_events(dedupe_key);

alter table public.realtime_events_v1
  add column source_outbox_id uuid
    references app.outbox_events(outbox_id) on delete restrict;

create unique index realtime_events_source_outbox_uq
  on public.realtime_events_v1(source_outbox_id);

create or replace function app.enqueue_realtime_domain_event()
returns trigger
language plpgsql
as $$
declare
  realtime_type text;
begin
  realtime_type := case new.event_type
    when 'STATE_PROJECTED' then 'STATE_UPDATED'
    when 'RECEIPT_FINALIZED' then 'RECEIPT_FINALIZED'
    else null
  end;

  if realtime_type is null then
    return new;
  end if;

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
    gen_random_uuid(),
    new.event_id,
    'domain:' || new.event_id::text || ':PUBLIC_ENTITY',
    realtime_type,
    case
      when new.event_type = 'RECEIPT_FINALIZED' then 'refresh'
      else new.entity_type
    end,
    case
      when new.event_type = 'RECEIPT_FINALIZED'
        then (new.payload ->> 'refresh_id')::uuid
      else new.entity_id
    end,
    new.entity_revision,
    'PUBLIC_ENTITY',
    case
      when new.event_type = 'RECEIPT_FINALIZED' then
        jsonb_build_object(
          'source_event_id', new.event_id,
          'refresh_id', new.payload ->> 'refresh_id',
          'occurred_at', new.occurred_at
        )
      else
        jsonb_build_object(
          'source_event_id', new.event_id,
          'occurred_at', new.occurred_at
        )
    end,
    'PENDING',
    new.occurred_at,
    new.occurred_at
  )
  on conflict (dedupe_key) do nothing;

  return new;
end;
$$;

create trigger domain_events_realtime_outbox
after insert on app.domain_events
for each row execute function app.enqueue_realtime_domain_event();

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
)
select
  gen_random_uuid(),
  d.event_id,
  'domain:' || d.event_id::text || ':PUBLIC_ENTITY',
  case d.event_type
    when 'STATE_PROJECTED' then 'STATE_UPDATED'
    when 'RECEIPT_FINALIZED' then 'RECEIPT_FINALIZED'
  end,
  case
    when d.event_type = 'RECEIPT_FINALIZED' then 'refresh'
    else d.entity_type
  end,
  case
    when d.event_type = 'RECEIPT_FINALIZED'
      then (d.payload ->> 'refresh_id')::uuid
    else d.entity_id
  end,
  d.entity_revision,
  'PUBLIC_ENTITY',
  case
    when d.event_type = 'RECEIPT_FINALIZED' then
      jsonb_build_object(
        'source_event_id', d.event_id,
        'refresh_id', d.payload ->> 'refresh_id',
        'occurred_at', d.occurred_at
      )
    else
      jsonb_build_object(
        'source_event_id', d.event_id,
        'occurred_at', d.occurred_at
      )
  end,
  'PENDING',
  d.occurred_at,
  d.occurred_at
from app.domain_events d
where d.event_type in ('STATE_PROJECTED', 'RECEIPT_FINALIZED')
on conflict (dedupe_key) do nothing;

create policy realtime_public_read_v1
on public.realtime_events_v1
for select
using (
  audience_type in ('PUBLIC_ENTITY', 'PUBLIC_AREA')
  and (expires_at is null or expires_at > now())
);

do $$
begin
  if exists (select 1 from pg_roles where rolname = 'anon') then
    execute 'grant usage on schema app to anon';
    execute 'grant select on public.realtime_events_v1 to anon';
  end if;
  if exists (select 1 from pg_roles where rolname = 'authenticated') then
    execute 'grant usage on schema app to authenticated';
    execute 'grant select on public.realtime_events_v1 to authenticated';
  end if;
end
$$;

do $$
begin
  if exists (
    select 1
    from pg_publication
    where pubname = 'supabase_realtime'
  ) and not exists (
    select 1
    from pg_publication_tables
    where pubname = 'supabase_realtime'
      and schemaname = 'public'
      and tablename = 'realtime_events_v1'
  ) then
    execute 'alter publication supabase_realtime add table public.realtime_events_v1';
  end if;
end
$$;
