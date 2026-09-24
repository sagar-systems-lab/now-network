\set ON_ERROR_STOP on

begin;

insert into app.locations(
  location_id,
  name,
  location_type,
  center,
  display_address
) values
(
  '30000000-0000-4000-8000-000000000001',
  'Parking Lot A',
  'PARKING',
  extensions.st_setsrid(extensions.st_makepoint(76.9000, 29.4000), 4326)::extensions.geography,
  'Near origin'
),
(
  '30000000-0000-4000-8000-000000000002',
  'Parking Lot B',
  'PARKING',
  extensions.st_setsrid(extensions.st_makepoint(76.9010, 29.4000), 4326)::extensions.geography,
  'Inside radius'
),
(
  '30000000-0000-4000-8000-000000000003',
  'Parking Lot Far',
  'PARKING',
  extensions.st_setsrid(extensions.st_makepoint(77.0500, 29.4000), 4326)::extensions.geography,
  'Outside radius'
);

insert into app.state_definitions(
  state_id,
  version,
  canonical_key,
  title,
  question,
  state_type,
  answer_schema,
  unit_code,
  freshness_policy,
  location_id,
  status
) values
(
  '31000000-0000-4000-8000-000000000001',
  1,
  'parking.a.available.v1',
  'Parking Lot A',
  'Available spaces',
  'NUMERIC',
  '{}'::jsonb,
  'spaces',
  '{}'::jsonb,
  '30000000-0000-4000-8000-000000000001',
  'ACTIVE'
),
(
  '31000000-0000-4000-8000-000000000002',
  1,
  'parking.b.available.v1',
  'Parking Lot B',
  'Available spaces',
  'NUMERIC',
  '{}'::jsonb,
  'spaces',
  '{}'::jsonb,
  '30000000-0000-4000-8000-000000000002',
  'ACTIVE'
),
(
  '31000000-0000-4000-8000-000000000003',
  1,
  'parking.far.available.v1',
  'Parking Lot Far',
  'Available spaces',
  'NUMERIC',
  '{}'::jsonb,
  'spaces',
  '{}'::jsonb,
  '30000000-0000-4000-8000-000000000003',
  'ACTIVE'
);

insert into app.live_states(
  state_id,
  state_version,
  current_value,
  observed_at,
  aging_at,
  fresh_until,
  verification_class
) values
(
  '31000000-0000-4000-8000-000000000001',
  1,
  '{"scaled_value":"4","scale":0,"unit":"spaces"}'::jsonb,
  now() - interval '1 minute',
  now() + interval '4 minutes',
  now() + interval '9 minutes',
  'FAST'
),
(
  '31000000-0000-4000-8000-000000000002',
  1,
  '{"scaled_value":"2","scale":0,"unit":"spaces"}'::jsonb,
  now() - interval '1 minute',
  now() + interval '4 minutes',
  now() + interval '9 minutes',
  'FAST'
),
(
  '31000000-0000-4000-8000-000000000003',
  1,
  '{"scaled_value":"8","scale":0,"unit":"spaces"}'::jsonb,
  now() - interval '1 minute',
  now() + interval '4 minutes',
  now() + interval '9 minutes',
  'FAST'
);

do $$
declare
  result_count integer;
  first_state uuid;
  first_distance double precision;
  next_state uuid;
begin
  select count(*)
  into result_count
  from app.query_nearby_states_v1(29.4000, 76.9000, 1000, 10);

  if result_count <> 2 then
    raise exception 'nearby query expected 2 rows, got %', result_count;
  end if;

  select state_id, distance_m
  into first_state, first_distance
  from app.query_nearby_states_v1(29.4000, 76.9000, 1000, 10)
  limit 1;

  if first_state <> '31000000-0000-4000-8000-000000000001'::uuid then
    raise exception 'nearby query is not distance ordered';
  end if;

  select state_id
  into next_state
  from app.query_nearby_states_v1(
    29.4000,
    76.9000,
    1000,
    10,
    first_distance,
    first_state
  )
  limit 1;

  if next_state <> '31000000-0000-4000-8000-000000000002'::uuid then
    raise exception 'nearby cursor did not advance deterministically';
  end if;
end
$$;

set local enable_seqscan = off;

do $$
declare
  plan_line text;
  plan_text text := '';
begin
  for plan_line in execute $query$
    explain
    select location_id
    from app.locations
    where extensions.st_dwithin(
      center,
      extensions.st_setsrid(
        extensions.st_makepoint(76.9000, 29.4000),
        4326
      )::extensions.geography,
      1000
    )
  $query$
  loop
    plan_text := plan_text || plan_line || E'\n';
  end loop;

  if position('locations_center_gist' in plan_text) = 0 then
    raise exception 'nearby query plan did not use locations_center_gist: %', plan_text;
  end if;
end
$$;

rollback;
