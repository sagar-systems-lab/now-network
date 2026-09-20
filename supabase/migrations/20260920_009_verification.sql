do $$ begin
  create type app.verification_status as enum (
    'NOT_STARTED', 'QUEUED', 'RUNNING', 'WAITING_FOR_MORE_EVIDENCE', 'VERIFIED',
    'REJECTED', 'CONFLICT', 'EXPIRED', 'INTERNAL_ERROR'
  );
exception when duplicate_object then null;
end $$;

create table app.verification_results (
  verification_result_id uuid primary key,
  refresh_id uuid not null references app.refresh_requests(refresh_id) on delete restrict,
  evidence_set_revision bigint not null check (evidence_set_revision > 0),
  policy_version integer not null check (policy_version > 0),
  status app.verification_status not null,
  reason_codes text[] not null default '{}',
  evidence_ids uuid[] not null,
  final_answer jsonb,
  verification_trace jsonb not null,
  location_summary jsonb,
  freshness_summary jsonb,
  media_integrity_summary jsonb,
  replay_summary jsonb,
  conflict_summary jsonb,
  execution_hash bytea not null,
  canonical_digest bytea not null,
  verifier_build text not null,
  started_at timestamptz,
  completed_at timestamptz,
  created_at timestamptz not null default now(),
  unique (refresh_id, evidence_set_revision, policy_version),
  check (completed_at is null or started_at is null or completed_at >= started_at),
  check (cardinality(evidence_ids) > 0),
  check (length(btrim(verifier_build)) > 0)
);
create index verification_results_refresh_status_idx on app.verification_results(refresh_id, status);
alter table app.verification_results enable row level security;

alter table app.live_states
  add constraint live_states_latest_verification_fk
  foreign key (latest_verification_result_id) references app.verification_results(verification_result_id) on delete restrict;
alter table app.state_history
  add constraint state_history_verification_fk
  foreign key (verification_result_id) references app.verification_results(verification_result_id) on delete restrict;
