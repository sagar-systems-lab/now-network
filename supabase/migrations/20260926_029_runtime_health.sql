create table app.runtime_health (
  worker_id text primary key,
  last_seen_at timestamptz not null,
  last_job_at timestamptz,
  build_version text not null,
  last_result text not null check (last_result in ('SUCCESS', 'FAILURE')),
  summary jsonb not null default '{}'::jsonb,
  updated_at timestamptz not null default now(),
  check (length(btrim(worker_id)) > 0),
  check (length(btrim(build_version)) > 0),
  check (last_job_at is null or last_job_at <= last_seen_at)
);

create index runtime_health_seen_idx
  on app.runtime_health(last_seen_at desc);

alter table app.runtime_health enable row level security;

create or replace function app.runtime_backlog_snapshot_v1(
  p_observed_at timestamptz
)
returns table (
  pending_verification_count bigint,
  oldest_pending_verification_age_seconds bigint,
  pending_settlement_count bigint,
  oldest_pending_settlement_age_seconds bigint,
  pending_outbox_count bigint,
  oldest_pending_outbox_age_seconds bigint
)
language sql
stable
set search_path = pg_catalog, app
as $$
  with
  verification_backlog as (
    select
      count(*)::bigint as pending_count,
      min(updated_at) as oldest_at
    from app.refresh_requests
    where status in ('EVIDENCE_SUBMITTED', 'VERIFYING')
  ),
  settlement_backlog as (
    select
      count(*)::bigint as pending_count,
      min(created_at) as oldest_at
    from app.settlement_operations
    where status in (
      'ELIGIBLE',
      'BUILDING',
      'SUBMITTING',
      'SUBMITTED',
      'VERIFYING',
      'CONFIRMED',
      'FINALIZING',
      'NOT_SETTLED'
    )
  ),
  outbox_backlog as (
    select
      count(*)::bigint as pending_count,
      min(created_at) as oldest_at
    from app.outbox_events
    where status in ('PENDING', 'PUBLISHING', 'RETRY_WAIT')
  )
  select
    verification_backlog.pending_count,
    case
      when verification_backlog.oldest_at is null then null
      else greatest(
        0,
        floor(extract(epoch from (p_observed_at - verification_backlog.oldest_at)))
      )::bigint
    end,
    settlement_backlog.pending_count,
    case
      when settlement_backlog.oldest_at is null then null
      else greatest(
        0,
        floor(extract(epoch from (p_observed_at - settlement_backlog.oldest_at)))
      )::bigint
    end,
    outbox_backlog.pending_count,
    case
      when outbox_backlog.oldest_at is null then null
      else greatest(
        0,
        floor(extract(epoch from (p_observed_at - outbox_backlog.oldest_at)))
      )::bigint
    end
  from verification_backlog, settlement_backlog, outbox_backlog;
$$;

revoke all on function app.runtime_backlog_snapshot_v1(timestamptz)
  from public;
