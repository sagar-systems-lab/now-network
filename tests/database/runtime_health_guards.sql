\set ON_ERROR_STOP on

begin;

do $$
declare
  rls_enabled boolean;
  function_count integer;
  index_count integer;
begin
  select c.relrowsecurity
  into rls_enabled
  from pg_class c
  join pg_namespace n on n.oid = c.relnamespace
  where n.nspname = 'app'
    and c.relname = 'runtime_health'
    and c.relkind = 'r';

  if coalesce(rls_enabled, false) = false then
    raise exception 'runtime health RLS is disabled or table is missing';
  end if;

  select count(*)
  into function_count
  from pg_proc p
  join pg_namespace n on n.oid = p.pronamespace
  where n.nspname = 'app'
    and p.proname = 'runtime_backlog_snapshot_v1';

  if function_count <> 1 then
    raise exception 'runtime backlog snapshot function missing';
  end if;

  select count(*)
  into index_count
  from pg_indexes
  where schemaname = 'app'
    and tablename = 'runtime_health'
    and indexname = 'runtime_health_seen_idx';

  if index_count <> 1 then
    raise exception 'runtime health recency index missing';
  end if;
end
$$;

insert into app.runtime_health(
  worker_id,
  last_seen_at,
  last_job_at,
  build_version,
  last_result,
  summary,
  updated_at
) values (
  'guard-worker',
  '2026-09-26T09:30:00Z',
  '2026-09-26T09:30:00Z',
  'guard-build',
  'SUCCESS',
  '{"settlement":{"finalized":1}}'::jsonb,
  '2026-09-26T09:30:00Z'
);

insert into app.runtime_health(
  worker_id,
  last_seen_at,
  last_job_at,
  build_version,
  last_result,
  summary,
  updated_at
) values (
  'guard-worker',
  '2026-09-26T09:31:00Z',
  '2026-09-26T09:31:00Z',
  'guard-build-2',
  'SUCCESS',
  '{}'::jsonb,
  '2026-09-26T09:31:00Z'
)
on conflict (worker_id) do update
set
  last_seen_at = excluded.last_seen_at,
  last_job_at = excluded.last_job_at,
  build_version = excluded.build_version,
  last_result = excluded.last_result,
  summary = excluded.summary,
  updated_at = excluded.updated_at;

do $$
declare
  heartbeat_count integer;
  heartbeat_build text;
  snapshot_count integer;
  verification_count bigint;
  settlement_count bigint;
  outbox_count bigint;
begin
  select count(*), max(build_version)
  into heartbeat_count, heartbeat_build
  from app.runtime_health
  where worker_id = 'guard-worker';

  if heartbeat_count <> 1 or heartbeat_build <> 'guard-build-2' then
    raise exception 'worker heartbeat upsert contract failed';
  end if;

  select
    count(*),
    max(pending_verification_count),
    max(pending_settlement_count),
    max(pending_outbox_count)
  into
    snapshot_count,
    verification_count,
    settlement_count,
    outbox_count
  from app.runtime_backlog_snapshot_v1('2026-09-26T09:31:00Z');

  if snapshot_count <> 1 then
    raise exception 'runtime backlog snapshot returned the wrong shape';
  end if;

  if verification_count < 0 or settlement_count < 0 or outbox_count < 0 then
    raise exception 'runtime backlog snapshot returned a negative count';
  end if;
end
$$;

rollback;
