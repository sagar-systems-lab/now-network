do $$ begin
  create type app.refresh_status as enum (
    'DRAFT', 'AWAITING_FUNDING', 'FUNDED', 'AVAILABLE', 'CLAIMED',
    'CAPTURE_IN_PROGRESS', 'EVIDENCE_SUBMITTED', 'VERIFYING',
    'ADDITIONAL_VERIFICATION', 'CONFLICT', 'VERIFIED', 'SETTLEMENT_PENDING',
    'SETTLEMENT_VERIFYING', 'COMPLETED', 'CANCELLED', 'EXPIRED', 'FAILED'
  );
exception when duplicate_object then null;
end $$;

create table app.refresh_requests (
  refresh_id uuid primary key,
  state_id uuid not null references app.state_definitions(state_id) on delete restrict,
  state_version integer not null check (state_version > 0),
  requester_actor_id uuid not null references app.actors(actor_id) on delete restrict,
  status app.refresh_status not null,
  verification_class app.verification_class not null,
  required_witnesses smallint not null,
  max_witnesses smallint not null,
  proof_policy_snapshot jsonb not null,
  proof_policy_digest bytea not null,
  intent_core_hash bytea not null,
  execution_hash bytea,
  refresh_expires_at timestamptz not null,
  evidence_deadline timestamptz not null,
  reward_mint text not null,
  chain_refresh_address text,
  chain_status text,
  chain_total_funded numeric(20,0) not null default 0 check (chain_total_funded >= 0),
  chain_locked_reward numeric(20,0) check (chain_locked_reward is null or chain_locked_reward >= 0),
  chain_observed_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  revision bigint not null default 1 check (revision > 0),
  check (required_witnesses between 1 and 3),
  check (max_witnesses between required_witnesses and 3),
  check (created_at < evidence_deadline),
  check (evidence_deadline < refresh_expires_at),
  check (length(btrim(reward_mint)) > 0)
);
create index refresh_requests_state_idx on app.refresh_requests(state_id, state_version);
create index refresh_requests_active_idx on app.refresh_requests(status, refresh_expires_at)
  where status not in ('COMPLETED', 'CANCELLED', 'EXPIRED', 'FAILED');
alter table app.refresh_requests enable row level security;

alter table app.live_states
  add constraint live_states_latest_refresh_fk
  foreign key (latest_refresh_id) references app.refresh_requests(refresh_id) on delete restrict;
alter table app.state_history
  add constraint state_history_refresh_fk
  foreign key (refresh_id) references app.refresh_requests(refresh_id) on delete restrict;
