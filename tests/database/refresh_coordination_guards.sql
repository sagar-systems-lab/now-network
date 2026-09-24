\set ON_ERROR_STOP on

begin;

insert into app.actors(actor_id, status)
values ('60000000-0000-4000-8000-000000000001', 'ACTIVE');

insert into app.locations(
  location_id,
  name,
  location_type,
  center
) values (
  '61000000-0000-4000-8000-000000000001',
  'Refresh Guard Location',
  'PARKING',
  extensions.st_setsrid(
    extensions.st_makepoint(76.9000, 29.4000),
    4326
  )::extensions.geography
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
) values (
  '62000000-0000-4000-8000-000000000001',
  1,
  'parking.available_spaces.v1',
  'Parking availability',
  'Available spaces',
  'NUMERIC',
  '{"type":"integer","minimum":0}'::jsonb,
  'spaces',
  '{}'::jsonb,
  '61000000-0000-4000-8000-000000000001',
  'ACTIVE'
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
  coordinator_version,
  creator_wallet_address,
  funding_target_atomic,
  payout_rule,
  chain_refresh_id,
  state_id_digest
) values (
  '63000000-0000-4000-8000-000000000001',
  '62000000-0000-4000-8000-000000000001',
  1,
  '60000000-0000-4000-8000-000000000001',
  'DRAFT',
  'FAST',
  1,
  1,
  '{"template_key":"parking.available_spaces.v1"}'::jsonb,
  decode(repeat('11', 32), 'hex'),
  decode(repeat('22', 32), 'hex'),
  now() + interval '15 minutes',
  now() + interval '13 minutes',
  'So11111111111111111111111111111111111111112',
  1,
  '11111111111111111111111111111111',
  1000000,
  'SINGLE_WINNER_ALL',
  decode(repeat('33', 32), 'hex'),
  decode(repeat('44', 32), 'hex')
);

do $$
begin
  begin
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
      coordinator_version
    ) values (
      '63000000-0000-4000-8000-000000000002',
      '62000000-0000-4000-8000-000000000001',
      1,
      '60000000-0000-4000-8000-000000000001',
      'DRAFT',
      'FAST',
      1,
      1,
      '{}'::jsonb,
      decode(repeat('55', 32), 'hex'),
      decode(repeat('66', 32), 'hex'),
      now() + interval '15 minutes',
      now() + interval '13 minutes',
      'So11111111111111111111111111111111111111112',
      1
    );
    raise exception 'coordinator v1 row unexpectedly accepted without frozen terms';
  exception
    when check_violation then null;
  end;
end
$$;

do $$
begin
  begin
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
      coordinator_version,
      creator_wallet_address,
      funding_target_atomic,
      payout_rule,
      chain_refresh_id,
      state_id_digest
    )
    select
      '63000000-0000-4000-8000-000000000003',
      state_id,
      state_version,
      requester_actor_id,
      'DRAFT',
      verification_class,
      required_witnesses,
      max_witnesses,
      proof_policy_snapshot,
      proof_policy_digest,
      decode(repeat('77', 32), 'hex'),
      refresh_expires_at,
      evidence_deadline,
      reward_mint,
      1,
      creator_wallet_address,
      funding_target_atomic,
      payout_rule,
      chain_refresh_id,
      decode(repeat('88', 32), 'hex')
    from app.refresh_requests
    where refresh_id = '63000000-0000-4000-8000-000000000001';

    raise exception 'duplicate chain refresh identity unexpectedly accepted';
  exception
    when unique_violation then null;
  end;
end
$$;

update app.refresh_requests
set
  status = 'AWAITING_FUNDING',
  funding_operation_id = '65000000-0000-4000-8000-000000000001',
  chain_refresh_address = 'RefreshPdaGuard11111111111111111111111111111',
  chain_status = 'INTENT_READY',
  revision = revision + 1
where refresh_id = '63000000-0000-4000-8000-000000000001';

do $$
begin
  begin
    update app.refresh_requests
    set
      chain_refresh_address = null
    where refresh_id = '63000000-0000-4000-8000-000000000001';
    raise exception 'funding state unexpectedly accepted without chain address';
  exception
    when check_violation then null;
  end;
end
$$;

insert into app.refresh_contributions(
  contribution_id,
  refresh_id,
  actor_id,
  wallet_address,
  operation_id,
  amount_atomic,
  chain_contribution_address,
  chain_signature,
  chain_commitment,
  status
) values (
  '64000000-0000-4000-8000-000000000001',
  '63000000-0000-4000-8000-000000000001',
  '60000000-0000-4000-8000-000000000001',
  '11111111111111111111111111111111',
  '65000000-0000-4000-8000-000000000001',
  1000000,
  'Contribution111111111111111111111111111111',
  'RefreshFundingSignatureGuard0001',
  'confirmed',
  'CONFIRMED'
);

do $$
begin
  begin
    insert into app.refresh_contributions(
      contribution_id,
      refresh_id,
      actor_id,
      wallet_address,
      operation_id,
      amount_atomic,
      chain_contribution_address,
      chain_signature,
      chain_commitment,
      status
    ) values (
      '64000000-0000-4000-8000-000000000002',
      '63000000-0000-4000-8000-000000000001',
      '60000000-0000-4000-8000-000000000001',
      '11111111111111111111111111111111',
      '65000000-0000-4000-8000-000000000002',
      1000000,
      'Contribution111111111111111111111111111112',
      'RefreshFundingSignatureGuard0001',
      'confirmed',
      'CONFIRMED'
    );
    raise exception 'duplicate chain signature unexpectedly accepted';
  exception
    when unique_violation then null;
  end;
end
$$;

rollback;
