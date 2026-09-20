create table app.security_events (
  security_event_id uuid primary key,
  actor_id uuid references app.actors(actor_id) on delete restrict,
  refresh_id uuid references app.refresh_requests(refresh_id) on delete restrict,
  evidence_id uuid references app.evidence_packets(evidence_id) on delete restrict,
  severity text not null,
  reason_code text not null,
  metadata jsonb,
  created_at timestamptz not null default now(),
  check (length(btrim(severity)) > 0),
  check (length(btrim(reason_code)) > 0)
);
create index security_events_actor_time_idx on app.security_events(actor_id, created_at desc) where actor_id is not null;
create index security_events_refresh_time_idx on app.security_events(refresh_id, created_at desc) where refresh_id is not null;
create index security_events_evidence_time_idx on app.security_events(evidence_id, created_at desc) where evidence_id is not null;
alter table app.security_events enable row level security;

alter table app.receipt_annotations
  add constraint receipt_annotations_security_event_fk
  foreign key (security_event_id) references app.security_events(security_event_id) on delete restrict;

create trigger security_events_append_only
before update or delete on app.security_events
for each row execute function app.reject_append_only_mutation();
