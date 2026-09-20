create table app.live_states (
  state_id uuid primary key references app.state_definitions(state_id) on delete restrict,
  state_version integer not null check (state_version > 0),
  current_value jsonb,
  current_value_digest bytea,
  observed_at timestamptz,
  observation_earliest timestamptz,
  observation_latest timestamptz,
  aging_at timestamptz,
  fresh_until timestamptz,
  verification_class app.verification_class,
  latest_verification_result_id uuid,
  latest_refresh_id uuid,
  conflict_active boolean not null default false,
  revision bigint not null default 1 check (revision > 0),
  updated_at timestamptz not null default now(),
  check (observation_earliest is null or observation_latest is null or observation_earliest <= observation_latest),
  check (observed_at is null or aging_at is null or observed_at <= aging_at),
  check (aging_at is null or fresh_until is null or aging_at <= fresh_until)
);
alter table app.live_states enable row level security;

create table app.state_history (
  history_id uuid primary key,
  state_id uuid not null references app.state_definitions(state_id) on delete restrict,
  state_version integer not null check (state_version > 0),
  refresh_id uuid,
  verification_result_id uuid,
  value jsonb,
  value_digest bytea,
  observed_at timestamptz not null,
  aging_at timestamptz not null,
  fresh_until timestamptz not null,
  verification_class app.verification_class not null,
  created_at timestamptz not null default now(),
  check (observed_at <= aging_at),
  check (aging_at <= fresh_until)
);
create index state_history_state_observed_idx on app.state_history(state_id, observed_at desc);
create index state_history_refresh_idx on app.state_history(refresh_id);
create index state_history_verification_idx on app.state_history(verification_result_id);
alter table app.state_history enable row level security;
