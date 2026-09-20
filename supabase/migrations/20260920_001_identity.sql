create schema if not exists app;
create schema if not exists api;
create schema if not exists extensions;

create table app.actors (
  actor_id uuid primary key,
  status text not null check (status in ('ACTIVE', 'DISABLED', 'RESTRICTED')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  last_seen_at timestamptz,
  revision bigint not null default 1 check (revision > 0)
);
alter table app.actors enable row level security;

create table app.actor_auth_principals (
  auth_user_id uuid primary key,
  actor_id uuid not null references app.actors(actor_id) on delete restrict,
  principal_type text not null,
  created_at timestamptz not null default now(),
  last_seen_at timestamptz,
  check (length(btrim(principal_type)) > 0)
);
create index actor_auth_principals_actor_idx on app.actor_auth_principals(actor_id);
alter table app.actor_auth_principals enable row level security;

create table app.wallet_bindings (
  wallet_binding_id uuid primary key,
  actor_id uuid not null references app.actors(actor_id) on delete restrict,
  wallet_address text not null,
  cluster text not null,
  status text not null check (status in ('ACTIVE', 'REVOKED', 'SUPERSEDED')),
  verified_at timestamptz not null,
  created_at timestamptz not null default now(),
  last_used_at timestamptz,
  revision bigint not null default 1 check (revision > 0),
  unique (cluster, wallet_address),
  check (length(btrim(wallet_address)) > 0),
  check (length(btrim(cluster)) > 0)
);
create index wallet_bindings_actor_idx on app.wallet_bindings(actor_id);
alter table app.wallet_bindings enable row level security;
