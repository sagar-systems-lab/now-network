create table app.refresh_acceptances (
  acceptance_id uuid primary key,
  refresh_id uuid not null references app.refresh_requests(refresh_id) on delete restrict,
  actor_id uuid not null references app.actors(actor_id) on delete restrict,
  wallet_address text not null,
  claim_slot smallint check (claim_slot between 0 and 2),
  claim_deadline timestamptz,
  chain_signature text,
  chain_status text,
  status text not null check (status in (
    'EMPTY', 'PREPARING', 'WALLET_PENDING', 'SUBMITTED', 'CONFIRMING', 'CLAIMED',
    'CAPTURE_ACTIVE', 'EVIDENCE_COMMITTED', 'RELEASE_ELIGIBLE', 'RELEASED',
    'EXPIRED', 'FAILED', 'UNKNOWN'
  )),
  accepted_at timestamptz not null default now(),
  released_at timestamptz,
  revision bigint not null default 1 check (revision > 0),
  unique (refresh_id, actor_id),
  check (length(btrim(wallet_address)) > 0),
  check (released_at is null or released_at >= accepted_at)
);
create unique index refresh_acceptances_active_slot_uq
  on app.refresh_acceptances(refresh_id, claim_slot)
  where claim_slot is not null and status in (
    'PREPARING', 'WALLET_PENDING', 'SUBMITTED', 'CONFIRMING', 'CLAIMED',
    'CAPTURE_ACTIVE', 'EVIDENCE_COMMITTED', 'RELEASE_ELIGIBLE', 'UNKNOWN'
  );
create index refresh_acceptances_refresh_status_idx on app.refresh_acceptances(refresh_id, status);
create index refresh_acceptances_actor_idx on app.refresh_acceptances(actor_id, status);
alter table app.refresh_acceptances enable row level security;
