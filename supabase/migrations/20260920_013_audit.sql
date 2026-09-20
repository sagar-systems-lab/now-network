create table app.domain_events (
  event_id uuid primary key,
  sequence_no bigint generated always as identity unique,
  entity_type text not null,
  entity_id uuid not null,
  event_type text not null,
  actor_id uuid references app.actors(actor_id) on delete restrict,
  operation_id uuid,
  correlation_id uuid,
  entity_revision bigint check (entity_revision is null or entity_revision > 0),
  payload_digest bytea,
  payload jsonb,
  occurred_at timestamptz not null default now(),
  check (length(btrim(entity_type)) > 0),
  check (length(btrim(event_type)) > 0)
);
create index domain_events_entity_sequence_idx on app.domain_events(entity_type, entity_id, sequence_no);
create index domain_events_entity_time_idx on app.domain_events(entity_type, entity_id, occurred_at desc);
create index domain_events_correlation_sequence_idx on app.domain_events(correlation_id, sequence_no) where correlation_id is not null;
create index domain_events_operation_idx on app.domain_events(operation_id) where operation_id is not null;
create index domain_events_occurred_idx on app.domain_events(occurred_at);
alter table app.domain_events enable row level security;

create or replace function app.reject_append_only_mutation()
returns trigger
language plpgsql
as $$
begin
  raise exception 'append-only relation cannot be updated or deleted';
end;
$$;

create trigger domain_events_append_only
before update or delete on app.domain_events
for each row execute function app.reject_append_only_mutation();

create trigger receipt_annotations_append_only
before update or delete on app.receipt_annotations
for each row execute function app.reject_append_only_mutation();
