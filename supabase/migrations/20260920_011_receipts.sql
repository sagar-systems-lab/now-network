do $$ begin
  create type app.receipt_status as enum ('NONE', 'DRAFT', 'CONFIRMED', 'FINALIZING', 'FINAL', 'ANNOTATED');
exception when duplicate_object then null;
end $$;

create table app.receipts (
  receipt_id uuid primary key,
  refresh_id uuid not null unique references app.refresh_requests(refresh_id) on delete restrict,
  state_id uuid not null references app.state_definitions(state_id) on delete restrict,
  verification_result_id uuid not null references app.verification_results(verification_result_id) on delete restrict,
  settlement_id uuid not null references app.settlement_operations(settlement_id) on delete restrict,
  status app.receipt_status not null,
  final_value jsonb not null,
  observed_at timestamptz not null,
  verification_class app.verification_class not null,
  reward_amount_atomic numeric(20,0) not null check (reward_amount_atomic >= 0),
  reward_mint text not null,
  verification_digest bytea not null,
  settlement_signature text,
  chain_commitment text,
  receipt_digest bytea not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  finalized_at timestamptz,
  revision bigint not null default 1 check (revision > 0),
  check (length(btrim(reward_mint)) > 0)
);
create index receipts_state_observed_idx on app.receipts(state_id, observed_at desc);
alter table app.receipts enable row level security;

create table app.receipt_annotations (
  annotation_id uuid primary key,
  receipt_id uuid not null references app.receipts(receipt_id) on delete restrict,
  annotation_type text not null,
  message text not null,
  security_event_id uuid,
  created_at timestamptz not null default now(),
  check (length(btrim(annotation_type)) > 0),
  check (length(btrim(message)) > 0)
);
create index receipt_annotations_receipt_idx on app.receipt_annotations(receipt_id, created_at);
alter table app.receipt_annotations enable row level security;
