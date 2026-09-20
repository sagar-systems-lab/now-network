create table app.idempotency_records (
  idempotency_key text primary key,
  actor_id uuid references app.actors(actor_id) on delete restrict,
  operation_type text not null,
  request_hash bytea not null,
  status text not null,
  response_code integer,
  response_body jsonb,
  operation_id uuid,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null,
  check (length(btrim(idempotency_key)) > 0),
  check (length(btrim(operation_type)) > 0),
  check (length(btrim(status)) > 0),
  check (expires_at > created_at)
);
create index idempotency_records_actor_operation_idx on app.idempotency_records(actor_id, operation_type);
create index idempotency_records_operation_id_idx on app.idempotency_records(operation_id) where operation_id is not null;
create index idempotency_records_expiry_idx on app.idempotency_records(expires_at);
alter table app.idempotency_records enable row level security;
