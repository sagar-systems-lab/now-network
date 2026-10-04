-- Actor-owned presentation data. Access is through the authenticated NOW API.
-- Existing operation, evidence and settlement tables remain authoritative.
create table app.actor_profiles (
  actor_id uuid primary key references app.actors(actor_id) on delete restrict,
  display_name text not null default '' check (char_length(display_name) <= 64),
  avatar_object_key text,
  updated_at timestamptz not null default now(),
  revision bigint not null default 1 check (revision > 0)
);
alter table app.actor_profiles enable row level security;

create table app.actor_preferences (
  actor_id uuid primary key references app.actors(actor_id) on delete restrict,
  notification_preferences jsonb not null default '{"opportunities":false,"proof":true,"payments":true,"security":true,"quiet_enabled":false,"quiet_start":"22:00","quiet_end":"08:00","timezone":"UTC","area_ids":[]}'::jsonb,
  payout_wallet_binding_id uuid references app.wallet_bindings(wallet_binding_id) on delete restrict,
  updated_at timestamptz not null default now(),
  revision bigint not null default 1 check (revision > 0),
  check (jsonb_typeof(notification_preferences) = 'object')
);
alter table app.actor_preferences enable row level security;

create table app.installations (
  installation_id uuid primary key,
  actor_id uuid not null references app.actors(actor_id) on delete restrict,
  auth_user_id uuid not null,
  auth_session_id uuid not null unique,
  device_name text not null check (char_length(device_name) between 1 and 100),
  app_version text not null check (char_length(app_version) between 1 and 40),
  push_token text check (char_length(push_token) <= 4096),
  push_permission boolean not null default false,
  created_at timestamptz not null default now(),
  last_seen_at timestamptz not null default now(),
  revoked_at timestamptz
);
create index installations_actor_seen_idx on app.installations(actor_id, last_seen_at desc);
create index installations_auth_revoked_idx on app.installations(auth_user_id, auth_session_id) where revoked_at is not null;
alter table app.installations enable row level security;

create table app.notifications (
  notification_id uuid primary key default gen_random_uuid(),
  actor_id uuid not null references app.actors(actor_id) on delete restrict,
  event_key text not null,
  category text not null check (category in ('proof','payments','security','opportunities')),
  title text not null,
  body text not null,
  destination text not null check (destination in ('activity','verification','payment','receipt','wallet','opportunity')),
  entity_id uuid,
  created_at timestamptz not null default now(),
  read_at timestamptz,
  unique(actor_id, event_key)
);
create index notifications_actor_date_idx on app.notifications(actor_id, created_at desc, notification_id desc);
create index notifications_actor_unread_idx on app.notifications(actor_id, created_at desc) where read_at is null;
alter table app.notifications enable row level security;

create table app.notification_deliveries (
  notification_id uuid not null references app.notifications(notification_id) on delete cascade,
  installation_id uuid not null references app.installations(installation_id) on delete cascade,
  status text not null default 'PENDING' check (status in ('PENDING','SENDING','SENT','SKIPPED','FAILED')),
  attempts integer not null default 0,
  next_attempt_at timestamptz not null default now(),
  lease_until timestamptz,
  sent_at timestamptz,
  primary key(notification_id, installation_id)
);
create index notification_deliveries_pending_idx on app.notification_deliveries(next_attempt_at) where status in ('PENDING','SENDING');
alter table app.notification_deliveries enable row level security;

revoke all on app.actor_profiles, app.actor_preferences, app.installations,
  app.notifications, app.notification_deliveries from anon, authenticated;

create or replace function app.notify_refresh_progress() returns trigger
language plpgsql set search_path = pg_catalog, app as $$
begin
  if old.status is not distinct from new.status then return new; end if;
  insert into app.notifications(actor_id, event_key, category, title, body, destination, entity_id)
  select distinct participant.actor_id,
    'refresh:' || new.refresh_id || ':' || new.revision,
    case when new.status::text in ('COMPLETED','SETTLEMENT_PENDING','SETTLEMENT_VERIFYING') then 'payments' else 'proof' end,
    case when new.status::text = 'COMPLETED' then 'Refresh completed'
         when new.status::text = 'VERIFIED' then 'Fresh proof verified'
         when new.status::text = 'CONFLICT' then 'Proof needs attention'
         else 'Refresh progress updated' end,
    'Open NOW to review the latest result and next steps.',
    case when new.status::text = 'COMPLETED' then 'payment'
         when new.status::text in ('VERIFIED','CONFLICT','ADDITIONAL_VERIFICATION') then 'verification'
         else 'activity' end,
    new.refresh_id
  from (
    select new.requester_actor_id as actor_id
    union select actor_id from app.refresh_acceptances where refresh_id = new.refresh_id
    union select actor_id from app.refresh_contributions where refresh_id = new.refresh_id
  ) participant
  on conflict (actor_id,event_key) do nothing;
  return new;
end;
$$;
revoke all on function app.notify_refresh_progress() from public;
create trigger refresh_progress_notification after update of status on app.refresh_requests
for each row execute function app.notify_refresh_progress();

create or replace function app.notify_final_receipt() returns trigger
language plpgsql set search_path = pg_catalog, app as $$
begin
  if new.status::text <> 'FINAL' then return new; end if;
  insert into app.notifications(actor_id,event_key,category,title,body,destination,entity_id)
  select distinct participant.actor_id, 'receipt:' || new.receipt_id, 'payments',
    'Your receipt is ready', 'Finalized on Solana. View your immutable receipt.', 'receipt', new.refresh_id
  from (
    select requester_actor_id as actor_id from app.refresh_requests where refresh_id = new.refresh_id
    union select actor_id from app.refresh_acceptances where refresh_id = new.refresh_id
    union select actor_id from app.refresh_contributions where refresh_id = new.refresh_id
  ) participant on conflict (actor_id,event_key) do nothing;
  return new;
end;
$$;
revoke all on function app.notify_final_receipt() from public;
create trigger receipt_ready_notification after insert or update of status on app.receipts
for each row execute function app.notify_final_receipt();

-- No public object URLs. The API signs avatar/proof reads after checking ownership.
do $$ begin
  if to_regclass('storage.buckets') is not null then
    insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
    values ('now-avatars','now-avatars',false,524288,array['image/png'])
    on conflict (id) do nothing;
  end if;
end $$;
