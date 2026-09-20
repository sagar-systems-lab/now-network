create table app.refresh_contributions (
  contribution_id uuid primary key,
  refresh_id uuid not null references app.refresh_requests(refresh_id) on delete restrict,
  actor_id uuid not null references app.actors(actor_id) on delete restrict,
  wallet_address text not null,
  operation_id uuid not null unique,
  amount_atomic numeric(20,0) not null check (amount_atomic >= 0),
  chain_contribution_address text,
  chain_signature text,
  chain_commitment text,
  status text not null check (status in ('PREPARING', 'SUBMITTED', 'CONFIRMING', 'CONFIRMED', 'FINALIZED', 'REFUNDED', 'FAILED', 'UNKNOWN')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check (length(btrim(wallet_address)) > 0)
);
create index refresh_contributions_refresh_idx on app.refresh_contributions(refresh_id, status);
create index refresh_contributions_actor_idx on app.refresh_contributions(actor_id);
alter table app.refresh_contributions enable row level security;
