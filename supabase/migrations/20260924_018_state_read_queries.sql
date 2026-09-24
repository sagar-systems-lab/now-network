create or replace function app.query_nearby_states_v1(
  p_lat double precision,
  p_lng double precision,
  p_radius_m integer,
  p_limit integer,
  p_after_distance_m double precision default null,
  p_after_state_id uuid default null
)
returns table (
  state_id uuid,
  title text,
  question text,
  state_type app.state_type,
  unit_code text,
  current_value jsonb,
  observed_at timestamptz,
  aging_at timestamptz,
  fresh_until timestamptz,
  verification_class app.verification_class,
  refresh_status app.refresh_status,
  conflict_active boolean,
  distance_m double precision,
  revision bigint
)
language sql
stable
set search_path = pg_catalog, app, extensions
as $$
  with query_point as (
    select extensions.st_setsrid(
      extensions.st_makepoint(p_lng, p_lat),
      4326
    )::extensions.geography as point
  ),
  candidates as (
    select
      sd.state_id,
      sd.title,
      sd.question,
      sd.state_type,
      sd.unit_code,
      ls.current_value,
      ls.observed_at,
      ls.aging_at,
      ls.fresh_until,
      ls.verification_class,
      ar.status as refresh_status,
      coalesce(ls.conflict_active, false) as conflict_active,
      extensions.st_distance(l.center, qp.point) as distance_m,
      coalesce(ls.revision, sd.revision) as revision
    from app.state_definitions sd
    join app.locations l on l.location_id = sd.location_id
    cross join query_point qp
    left join app.live_states ls on ls.state_id = sd.state_id
    left join lateral (
      select rr.status
      from app.refresh_requests rr
      where rr.state_id = sd.state_id
        and rr.state_version = sd.version
        and rr.status not in ('COMPLETED', 'CANCELLED', 'EXPIRED', 'FAILED')
      order by rr.created_at desc, rr.refresh_id desc
      limit 1
    ) ar on true
    where sd.status = 'ACTIVE'
      and p_lat between -90 and 90
      and p_lng between -180 and 180
      and p_radius_m between 1 and 50000
      and p_limit between 1 and 51
      and extensions.st_dwithin(l.center, qp.point, p_radius_m)
  )
  select
    candidates.state_id,
    candidates.title,
    candidates.question,
    candidates.state_type,
    candidates.unit_code,
    candidates.current_value,
    candidates.observed_at,
    candidates.aging_at,
    candidates.fresh_until,
    candidates.verification_class,
    candidates.refresh_status,
    candidates.conflict_active,
    candidates.distance_m,
    candidates.revision
  from candidates
  where
    (
      p_after_distance_m is null
      and p_after_state_id is null
    )
    or
    (
      p_after_distance_m is not null
      and p_after_state_id is not null
      and (
        candidates.distance_m > p_after_distance_m
        or (
          candidates.distance_m = p_after_distance_m
          and candidates.state_id > p_after_state_id
        )
      )
    )
  order by candidates.distance_m, candidates.state_id
  limit least(p_limit, 51);
$$;

revoke all on function app.query_nearby_states_v1(
  double precision,
  double precision,
  integer,
  integer,
  double precision,
  uuid
) from public;
