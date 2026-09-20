create table app.evidence_challenges (
  challenge_id uuid primary key,
  refresh_id uuid not null references app.refresh_requests(refresh_id) on delete restrict,
  acceptance_id uuid not null references app.refresh_acceptances(acceptance_id) on delete restrict,
  actor_id uuid not null references app.actors(actor_id) on delete restrict,
  wallet_address text not null,
  nonce_hash bytea not null,
  status text not null check (status in ('ISSUED', 'CONSUMED', 'EXPIRED', 'REVOKED')),
  issued_at timestamptz not null,
  expires_at timestamptz not null,
  consumed_at timestamptz,
  revoked_at timestamptz,
  policy_version integer not null check (policy_version > 0),
  created_at timestamptz not null default now(),
  check (expires_at > issued_at),
  check (consumed_at is null or consumed_at >= issued_at),
  check (revoked_at is null or revoked_at >= issued_at),
  check (length(btrim(wallet_address)) > 0)
);
create unique index evidence_challenges_one_issued_per_acceptance
  on app.evidence_challenges(acceptance_id) where status = 'ISSUED';
create index evidence_challenges_refresh_idx on app.evidence_challenges(refresh_id, status);
alter table app.evidence_challenges enable row level security;
