create extension if not exists postgis with schema extensions;

do $$ begin
  create type app.state_type as enum ('BINARY', 'NUMERIC', 'VISUAL');
exception when duplicate_object then null;
end $$;

do $$ begin
  create type app.verification_class as enum ('FAST', 'CORROBORATED', 'STRICT');
exception when duplicate_object then null;
end $$;

create table app.locations (
  location_id uuid primary key,
  name text not null,
  location_type text not null,
  center extensions.geography(Point,4326) not null,
  boundary extensions.geography(Polygon,4326),
  display_address text,
  privacy_class text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  revision bigint not null default 1 check (revision > 0),
  check (length(btrim(name)) > 0),
  check (length(btrim(location_type)) > 0)
);
create index locations_center_gist on app.locations using gist(center);
create index locations_boundary_gist on app.locations using gist(boundary) where boundary is not null;
alter table app.locations enable row level security;

create table app.state_definitions (
  state_id uuid primary key,
  version integer not null check (version > 0),
  canonical_key text not null,
  title text not null,
  question text not null,
  state_type app.state_type not null,
  answer_schema jsonb not null,
  unit_code text,
  freshness_policy jsonb not null,
  verification_template_id uuid,
  location_id uuid not null references app.locations(location_id) on delete restrict,
  status text not null check (status in ('ACTIVE', 'ARCHIVED', 'INVALID')),
  created_by_actor_id uuid references app.actors(actor_id) on delete restrict,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  revision bigint not null default 1 check (revision > 0),
  unique (canonical_key, version),
  check (length(btrim(canonical_key)) > 0),
  check (length(btrim(title)) > 0),
  check (length(btrim(question)) > 0)
);
create index state_definitions_location_idx on app.state_definitions(location_id);
create index state_definitions_status_idx on app.state_definitions(status);
alter table app.state_definitions enable row level security;
