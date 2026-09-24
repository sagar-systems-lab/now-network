create table app.wallet_binding_challenges (
  challenge_id uuid primary key,
  actor_id uuid not null references app.actors(actor_id) on delete restrict,
  auth_user_id uuid not null references app.actor_auth_principals(auth_user_id) on delete restrict,
  wallet_address text not null,
  cluster text not null,
  purpose text not null check (purpose = 'wallet_binding'),
  domain text not null check (domain = 'NOW Network'),
  message text not null,
  message_sha256 bytea not null,
  nonce_hash bytea not null,
  status text not null check (status in ('ISSUED', 'CONSUMED', 'EXPIRED', 'REVOKED')),
  issued_at timestamptz not null,
  expires_at timestamptz not null,
  consumed_at timestamptz,
  revoked_at timestamptz,
  created_at timestamptz not null default now(),
  check (expires_at > issued_at),
  check (consumed_at is null or consumed_at >= issued_at),
  check (revoked_at is null or revoked_at >= issued_at),
  check (length(btrim(wallet_address)) > 0),
  check (length(btrim(cluster)) > 0),
  check (octet_length(message_sha256) = 32),
  check (octet_length(nonce_hash) = 32)
);

create unique index wallet_binding_challenges_one_issued_per_principal
  on app.wallet_binding_challenges(auth_user_id)
  where status = 'ISSUED';
create index wallet_binding_challenges_actor_idx
  on app.wallet_binding_challenges(actor_id, status);
create index wallet_binding_challenges_expiry_idx
  on app.wallet_binding_challenges(expires_at)
  where status = 'ISSUED';

alter table app.wallet_binding_challenges enable row level security;
