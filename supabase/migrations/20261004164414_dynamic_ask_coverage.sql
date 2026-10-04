-- Policy identity belongs to the registered template, not to a place instance.
alter table app.state_definitions add column policy_template_key text;
update app.state_definitions set policy_template_key=canonical_key
where canonical_key in ('parking.available_spaces.v1','gate.open_closed.v1','visual.current_condition.v1');
alter table app.state_definitions add constraint state_policy_template_registered check (
  policy_template_key is null or policy_template_key in
    ('parking.available_spaces.v1','gate.open_closed.v1','visual.current_condition.v1')
);
create unique index state_location_policy_version_idx
  on app.state_definitions(location_id,policy_template_key,version)
  where policy_template_key is not null;

alter table app.locations add column ask_fingerprint text;
create unique index locations_ask_fingerprint_idx on app.locations(ask_fingerprint)
  where ask_fingerprint is not null;

-- One replaceable foreground presence per actor; no location history.
create table app.contributor_presence (
  actor_id uuid primary key references app.actors(actor_id) on delete cascade,
  center extensions.geography(Point,4326) not null,
  accuracy_m double precision not null check (accuracy_m >= 0 and accuracy_m <= 100),
  coverage_radius_m double precision not null default 2000 check (coverage_radius_m between 100 and 5000),
  available boolean not null default false,
  heartbeat_at timestamptz not null default now(),
  expires_at timestamptz not null,
  revision bigint not null default 1 check (revision > 0),
  check (expires_at > heartbeat_at and expires_at <= heartbeat_at + interval '180 seconds')
);
create index contributor_presence_center_gist on app.contributor_presence using gist(center) where available;
create index contributor_presence_expiry_idx on app.contributor_presence(expires_at);
alter table app.contributor_presence enable row level security;

-- Durable, bounded counters contain no target or device coordinates.
create table app.ask_rate_limits (
  actor_id uuid not null references app.actors(actor_id) on delete cascade,
  operation text not null check (operation in ('presence','coverage','resolve','new_location','new_state')),
  bucket_start timestamptz not null,
  requests integer not null check (requests > 0),
  primary key(actor_id,operation,bucket_start)
);
create index ask_rate_limits_expiry_idx on app.ask_rate_limits(bucket_start);
alter table app.ask_rate_limits enable row level security;
revoke all on app.contributor_presence,app.ask_rate_limits from public;
do $$
begin
  if exists (select 1 from pg_roles where rolname='anon') then
    revoke all on app.contributor_presence,app.ask_rate_limits from anon;
  end if;
  if exists (select 1 from pg_roles where rolname='authenticated') then
    revoke all on app.contributor_presence,app.ask_rate_limits from authenticated;
  end if;
end $$;
