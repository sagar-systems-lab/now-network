do $$ begin
  create type app.settlement_status as enum (
    'NOT_STARTED', 'ELIGIBLE', 'BUILDING', 'SUBMITTING', 'SUBMITTED', 'VERIFYING',
    'CONFIRMED', 'FINALIZING', 'FINALIZED', 'NOT_SETTLED', 'FAILED'
  );
exception when duplicate_object then null;
end $$;

do $$ begin
  create type app.refund_status as enum (
    'NOT_ELIGIBLE', 'ELIGIBLE', 'PREPARING', 'WALLET_PENDING', 'SUBMITTED', 'VERIFYING',
    'CONFIRMED', 'FINALIZED', 'NOT_REFUNDED', 'FAILED'
  );
exception when duplicate_object then null;
end $$;

create table app.settlement_operations (
  settlement_id uuid primary key,
  refresh_id uuid not null references app.refresh_requests(refresh_id) on delete restrict,
  verification_result_id uuid not null references app.verification_results(verification_result_id) on delete restrict,
  operation_id uuid not null unique,
  operation_hash bytea not null,
  verification_digest bytea not null,
  status app.settlement_status not null,
  chain_signature text,
  recent_blockhash text,
  last_valid_block_height bigint,
  chain_commitment text,
  attempt_count integer not null default 0 check (attempt_count >= 0),
  next_reconcile_at timestamptz,
  last_chain_observed_at timestamptz,
  last_error_code text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  confirmed_at timestamptz,
  finalized_at timestamptz,
  unique (refresh_id, operation_id),
  check (finalized_at is null or confirmed_at is null or finalized_at >= confirmed_at)
);
create index settlement_operations_active_idx on app.settlement_operations(status, next_reconcile_at)
  where status in ('ELIGIBLE', 'BUILDING', 'SUBMITTING', 'SUBMITTED', 'VERIFYING', 'CONFIRMED', 'FINALIZING');
create index settlement_operations_refresh_idx on app.settlement_operations(refresh_id);
alter table app.settlement_operations enable row level security;

create table app.refund_operations (
  refund_id uuid primary key,
  refresh_id uuid not null references app.refresh_requests(refresh_id) on delete restrict,
  contribution_id uuid not null references app.refresh_contributions(contribution_id) on delete restrict,
  actor_id uuid not null references app.actors(actor_id) on delete restrict,
  operation_id uuid not null unique,
  status app.refund_status not null,
  chain_signature text,
  last_valid_block_height bigint,
  attempt_count integer not null default 0 check (attempt_count >= 0),
  next_reconcile_at timestamptz,
  confirmed_at timestamptz,
  finalized_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check (finalized_at is null or confirmed_at is null or finalized_at >= confirmed_at)
);
create index refund_operations_active_idx on app.refund_operations(status, next_reconcile_at)
  where status in ('ELIGIBLE', 'PREPARING', 'WALLET_PENDING', 'SUBMITTED', 'VERIFYING', 'CONFIRMED');
alter table app.refund_operations enable row level security;
