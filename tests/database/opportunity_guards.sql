\set ON_ERROR_STOP on

begin;

insert into app.actors(actor_id, status) values
  ('80000000-0000-4000-8000-000000000001', 'ACTIVE'),
  ('80000000-0000-4000-8000-000000000002', 'ACTIVE'),
  ('80000000-0000-4000-8000-000000000003', 'ACTIVE');

insert into app.locations(
  location_id,
  name,
  location_type,
  center,
  display_address
) values
(
  '81000000-0000-4000-8000-000000000001',
  'Eligible A',
  'PARKING',
  extensions.st_setsrid(extensions.st_makepoint(76.9000, 29.4000), 4326)::extensions.geography,
  'Near origin'
),
(
  '81000000-0000-4000-8000-000000000002',
  'Eligible B',
  'PARKING',
  extensions.st_setsrid(extensions.st_makepoint(76.9010, 29.4000), 4326)::extensions.geography,
  'Inside radius'
),
(
  '81000000-0000-4000-8000-000000000003',
  'Own Request',
  'PARKING',
  extensions.st_setsrid(extensions.st_makepoint(76.9002, 29.4000), 4326)::extensions.geography,
  'Inside radius'
),
(
  '81000000-0000-4000-8000-000000000004',
  'Expired',
  'PARKING',
  extensions.st_setsrid(extensions.st_makepoint(76.9003, 29.4000), 4326)::extensions.geography,
  'Inside radius'
),
(
  '81000000-0000-4000-8000-000000000005',
  'Full',
  'PARKING',
  extensions.st_setsrid(extensions.st_makepoint(76.9004, 29.4000), 4326)::extensions.geography,
  'Inside radius'
),
(
  '81000000-0000-4000-8000-000000000006',
  'Far',
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
  '82000000-0000-4000-8000-000000000001', 1,
  'parking.guard.eligible_a.v1', 'Eligible A', 'Available spaces',
  'NUMERIC', '{"type":"integer","minimum":0}'::jsonb, 'spaces', '{}'::jsonb,
  '81000000-0000-4000-8000-000000000001', 'ACTIVE'
),
(
  '82000000-0000-4000-8000-000000000002', 1,
  'parking.guard.eligible_b.v1', 'Eligible B', 'Available spaces',
  'NUMERIC', '{"type":"integer","minimum":0}'::jsonb, 'spaces', '{}'::jsonb,
  '81000000-0000-4000-8000-000000000002', 'ACTIVE'
),
(
  '82000000-0000-4000-8000-000000000003', 1,
  'parking.guard.own.v1', 'Own Request', 'Available spaces',
  'NUMERIC', '{"type":"integer","minimum":0}'::jsonb, 'spaces', '{}'::jsonb,
  '81000000-0000-4000-8000-000000000003', 'ACTIVE'
),
(
  '82000000-0000-4000-8000-000000000004', 1,
  'parking.guard.expired.v1', 'Expired', 'Available spaces',
  'NUMERIC', '{"type":"integer","minimum":0}'::jsonb, 'spaces', '{}'::jsonb,
  '81000000-0000-4000-8000-000000000004', 'ACTIVE'
),
(
  '82000000-0000-4000-8000-000000000005', 1,
  'parking.guard.full.v1', 'Full', 'Available spaces',
  'NUMERIC', '{"type":"integer","minimum":0}'::jsonb, 'spaces', '{}'::jsonb,
  '81000000-0000-4000-8000-000000000005', 'ACTIVE'
),
(
  '82000000-0000-4000-8000-000000000006', 1,
  'parking.guard.far.v1', 'Far', 'Available spaces',
  'NUMERIC', '{"type":"integer","minimum":0}'::jsonb, 'spaces', '{}'::jsonb,
  '81000000-0000-4000-8000-000000000006', 'ACTIVE'
);

insert into app.refresh_requests(
  refresh_id,
  state_id,
  state_version,
  requester_actor_id,
  status,
  verification_class,
  required_witnesses,
  max_witnesses,
  proof_policy_snapshot,
  proof_policy_digest,
  intent_core_hash,
  refresh_expires_at,
  evidence_deadline,
  reward_mint,
  chain_total_funded,
  payout_rule,
  created_at
) values
(
  '83000000-0000-4000-8000-000000000001',
  '82000000-0000-4000-8000-000000000001',
  1,
  '80000000-0000-4000-8000-000000000002',
  'AVAILABLE',
  'FAST',
  1,
  1,
  '{"template_key":"parking.available_spaces.v1","capture":{"media_required":true,"location_required":true}}'::jsonb,
  decode(repeat('11', 32), 'hex'),
  decode(repeat('21', 32), 'hex'),
  now() + interval '15 minutes',
  now() + interval '13 minutes',
  'GuardMint',
  500000,
  'SINGLE_WINNER_ALL',
  now()
),
(
  '83000000-0000-4000-8000-000000000002',
  '82000000-0000-4000-8000-000000000002',
  1,
  '80000000-0000-4000-8000-000000000002',
  'AVAILABLE',
  'CORROBORATED',
  2,
  2,
  '{"template_key":"parking.available_spaces.v1","capture":{"media_required":true,"location_required":true}}'::jsonb,
  decode(repeat('12', 32), 'hex'),
  decode(repeat('22', 32), 'hex'),
  now() + interval '15 minutes',
  now() + interval '13 minutes',
  'GuardMint',
  900000,
  'EQUAL_SPLIT_REQUIRED_WITNESSES',
  now()
),
(
  '83000000-0000-4000-8000-000000000003',
  '82000000-0000-4000-8000-000000000003',
  1,
  '80000000-0000-4000-8000-000000000001',
  'AVAILABLE',
  'FAST',
  1,
  1,
  '{"template_key":"parking.available_spaces.v1","capture":{"media_required":true,"location_required":true}}'::jsonb,
  decode(repeat('13', 32), 'hex'),
  decode(repeat('23', 32), 'hex'),
  now() + interval '15 minutes',
  now() + interval '13 minutes',
  'GuardMint',
  500000,
  'SINGLE_WINNER_ALL',
  now()
),
(
  '83000000-0000-4000-8000-000000000004',
  '82000000-0000-4000-8000-000000000004',
  1,
  '80000000-0000-4000-8000-000000000002',
  'AVAILABLE',
  'FAST',
  1,
  1,
  '{"template_key":"parking.available_spaces.v1","capture":{"media_required":true,"location_required":true}}'::jsonb,
  decode(repeat('14', 32), 'hex'),
  decode(repeat('24', 32), 'hex'),
  now() - interval '5 minutes',
  now() - interval '10 minutes',
  'GuardMint',
  500000,
  'SINGLE_WINNER_ALL',
  now() - interval '30 minutes'
),
(
  '83000000-0000-4000-8000-000000000005',
  '82000000-0000-4000-8000-000000000005',
  1,
  '80000000-0000-4000-8000-000000000002',
  'AVAILABLE',
  'FAST',
  1,
  1,
  '{"template_key":"parking.available_spaces.v1","capture":{"media_required":true,"location_required":true}}'::jsonb,
  decode(repeat('15', 32), 'hex'),
  decode(repeat('25', 32), 'hex'),
  now() + interval '15 minutes',
  now() + interval '13 minutes',
  'GuardMint',
  500000,
  'SINGLE_WINNER_ALL',
  now()
),
(
  '83000000-0000-4000-8000-000000000006',
  '82000000-0000-4000-8000-000000000006',
  1,
  '80000000-0000-4000-8000-000000000002',
  'AVAILABLE',
  'FAST',
  1,
  1,
  '{"template_key":"parking.available_spaces.v1","capture":{"media_required":true,"location_required":true}}'::jsonb,
  decode(repeat('16', 32), 'hex'),
  decode(repeat('26', 32), 'hex'),
  now() + interval '15 minutes',
  now() + interval '13 minutes',
  'GuardMint',
  500000,
  'SINGLE_WINNER_ALL',
  now()
);

insert into app.refresh_acceptances(
  acceptance_id,
  refresh_id,
  actor_id,
  wallet_address,
  claim_slot,
  claim_deadline,
  status
) values
(
  '84000000-0000-4000-8000-000000000001',
  '83000000-0000-4000-8000-000000000002',
  '80000000-0000-4000-8000-000000000003',
  'GuardWalletA',
  0,
  now() + interval '10 minutes',
  'CLAIMED'
),
(
  '84000000-0000-4000-8000-000000000002',
  '83000000-0000-4000-8000-000000000005',
  '80000000-0000-4000-8000-000000000003',
  'GuardWalletB',
  0,
  now() + interval '10 minutes',
  'CLAIMED'
);

do $$
declare
  result_count integer;
  first_refresh uuid;
  first_distance double precision;
  next_refresh uuid;
  second_active integer;
  second_remaining integer;
begin
  select count(*)
  into result_count
  from app.query_nearby_opportunities_v1(
    '80000000-0000-4000-8000-000000000001',
    29.4000,
    76.9000,
    1000,
    10
  );

  if result_count <> 2 then
    raise exception 'opportunity query expected 2 claimable rows, got %', result_count;
  end if;

  select refresh_id, distance_m
  into first_refresh, first_distance
  from app.query_nearby_opportunities_v1(
    '80000000-0000-4000-8000-000000000001',
    29.4000,
    76.9000,
    1000,
    10
  )
  limit 1;

  if first_refresh <> '83000000-0000-4000-8000-000000000001'::uuid then
    raise exception 'opportunity query is not distance ordered';
  end if;

  select refresh_id
  into next_refresh
  from app.query_nearby_opportunities_v1(
    '80000000-0000-4000-8000-000000000001',
    29.4000,
    76.9000,
    1000,
    10,
    first_distance,
    first_refresh
  )
  limit 1;

  if next_refresh <> '83000000-0000-4000-8000-000000000002'::uuid then
    raise exception 'opportunity cursor did not advance deterministically';
  end if;

  select active_claims, remaining_slots
  into second_active, second_remaining
  from app.query_nearby_opportunities_v1(
    '80000000-0000-4000-8000-000000000001',
    29.4000,
    76.9000,
    1000,
    10
  )
  where refresh_id = '83000000-0000-4000-8000-000000000002'::uuid;

  if second_active <> 1 or second_remaining <> 1 then
    raise exception 'opportunity witness capacity projection is incorrect';
  end if;
end
$$;

update app.refresh_requests
set status = 'ADDITIONAL_VERIFICATION'
where refresh_id = '83000000-0000-4000-8000-000000000002'::uuid;

do $
declare
  additional_count integer;
begin
  select count(*)
  into additional_count
  from app.query_nearby_opportunities_v1(
    '80000000-0000-4000-8000-000000000001',
    29.4000,
    76.9000,
    1000,
    10
  )
  where refresh_id = '83000000-0000-4000-8000-000000000002'::uuid;

  if additional_count <> 1 then
    raise exception 'additional-verification opportunity was not discoverable';
  end if;
end
$;

insert into app.refresh_acceptances(
  acceptance_id,
  refresh_id,
  actor_id,
  wallet_address,
  claim_slot,
  claim_deadline,
  status
) values (
  '84000000-0000-4000-8000-000000000003',
  '83000000-0000-4000-8000-000000000002',
  '80000000-0000-4000-8000-000000000001',
  'GuardViewerWallet',
  1,
  now() + interval '10 minutes',
  'CLAIMED'
);

do $$
declare
  result_count integer;
begin
  select count(*)
  into result_count
  from app.query_nearby_opportunities_v1(
    '80000000-0000-4000-8000-000000000001',
    29.4000,
    76.9000,
    1000,
    10
  );

  if result_count <> 1 then
    raise exception 'actor active acceptance was not removed from opportunity feed';
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
    raise exception 'opportunity geo plan did not use locations_center_gist: %', plan_text;
  end if;
end
$$;

rollback;
