do $$ begin
  create type app.evidence_status as enum (
    'NONE', 'CAPTURING', 'CAPTURED_LOCAL', 'HASHING', 'UPLOAD_READY', 'UPLOADING',
    'UPLOADED', 'COMMITTING', 'COMMITTED', 'VERIFYING', 'VERIFIED', 'REJECTED',
    'CONFLICT', 'EXPIRED', 'FAILED'
  );
exception when duplicate_object then null;
end $$;

create table app.evidence_packets (
  evidence_id uuid primary key,
  refresh_id uuid not null references app.refresh_requests(refresh_id) on delete restrict,
  acceptance_id uuid not null references app.refresh_acceptances(acceptance_id) on delete restrict,
  challenge_id uuid not null unique references app.evidence_challenges(challenge_id) on delete restrict,
  actor_id uuid not null references app.actors(actor_id) on delete restrict,
  wallet_address text not null,
  state_id uuid not null references app.state_definitions(state_id) on delete restrict,
  state_version integer not null check (state_version > 0),
  intent_core_hash bytea not null,
  execution_hash bytea not null,
  answer_type app.state_type not null,
  answer_value jsonb not null,
  capture_started_monotonic_ms bigint,
  capture_completed_monotonic_ms bigint,
  server_observation_earliest timestamptz,
  server_observation_latest timestamptz,
  media_object_key text,
  media_sha256 bytea,
  perceptual_hash text,
  media_size_bytes bigint check (media_size_bytes is null or media_size_bytes >= 0),
  media_mime text,
  status app.evidence_status not null,
  created_at timestamptz not null default now(),
  committed_at timestamptz,
  updated_at timestamptz not null default now(),
  revision bigint not null default 1 check (revision > 0),
  check (capture_started_monotonic_ms is null or capture_completed_monotonic_ms is null or capture_started_monotonic_ms <= capture_completed_monotonic_ms),
  check (server_observation_earliest is null or server_observation_latest is null or server_observation_earliest <= server_observation_latest),
  check (length(btrim(wallet_address)) > 0)
);
create unique index evidence_packets_media_object_key_uq on app.evidence_packets(media_object_key)
  where media_object_key is not null;
create index evidence_packets_media_sha256_idx on app.evidence_packets(media_sha256)
  where media_sha256 is not null;
create index evidence_packets_refresh_status_idx on app.evidence_packets(refresh_id, status);
alter table app.evidence_packets enable row level security;

create table app.evidence_location_samples (
  sample_id uuid primary key,
  evidence_id uuid not null references app.evidence_packets(evidence_id) on delete restrict,
  sample_order smallint not null check (sample_order >= 0),
  point extensions.geography(Point,4326) not null,
  accuracy_m numeric check (accuracy_m is null or accuracy_m >= 0),
  provider text,
  mock_signal boolean,
  captured_offset_ms bigint,
  created_at timestamptz not null default now(),
  unique (evidence_id, sample_order)
);
create index evidence_location_samples_evidence_idx on app.evidence_location_samples(evidence_id, sample_order);
alter table app.evidence_location_samples enable row level security;
