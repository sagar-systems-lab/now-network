do $$ begin
  create type app.event_visibility as enum ('PUBLIC_ENTITY', 'PUBLIC_AREA', 'ACTOR_PRIVATE');
exception when duplicate_object then null;
end $$;

create table app.outbox_events (
  outbox_id uuid primary key,
  domain_event_id uuid references app.domain_events(event_id) on delete restrict,
  event_type text not null,
  payload jsonb not null,
  status text not null check (status in ('PENDING', 'PUBLISHING', 'PUBLISHED', 'RETRY_WAIT', 'DEAD_LETTER')),
  attempt_count integer not null default 0 check (attempt_count >= 0),
  next_attempt_at timestamptz,
  last_error text,
  created_at timestamptz not null default now(),
  published_at timestamptz,
  check (length(btrim(event_type)) > 0)
);
create index outbox_events_pending_idx on app.outbox_events(status, next_attempt_at)
  where status in ('PENDING', 'RETRY_WAIT');
alter table app.outbox_events enable row level security;

create table public.realtime_events_v1 (
  realtime_event_id uuid primary key,
  event_type text not null,
  entity_type text not null,
  entity_id uuid not null,
  entity_revision bigint check (entity_revision is null or entity_revision > 0),
  audience_type app.event_visibility not null,
  audience_actor_id uuid references app.actors(actor_id) on delete restrict,
  area_key text,
  payload jsonb not null,
  created_at timestamptz not null default now(),
  expires_at timestamptz,
  check (length(btrim(event_type)) > 0),
  check (length(btrim(entity_type)) > 0),
  check (audience_type <> 'ACTOR_PRIVATE' or audience_actor_id is not null),
  check (expires_at is null or expires_at > created_at)
);
create index realtime_events_entity_idx on public.realtime_events_v1(entity_type, entity_id, entity_revision);
create index realtime_events_actor_idx on public.realtime_events_v1(audience_actor_id, created_at)
  where audience_type = 'ACTOR_PRIVATE';
create index realtime_events_expiry_idx on public.realtime_events_v1(expires_at) where expires_at is not null;
alter table public.realtime_events_v1 enable row level security;
