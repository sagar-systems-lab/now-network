begin;

set local session_replication_role = replica;

insert into app.settlement_operations(
  settlement_id,
  refresh_id,
  verification_result_id,
  operation_id,
  operation_hash,
  verification_digest,
  execution_hash,
  recipient_mask,
  recipient_wallets,
  chain_refresh_id,
  refresh_expires_at,
  reward_mint,
  locked_reward_atomic,
  chain_refresh_address,
  status
) values (
  '10000000-0000-0000-0000-000000000001',
  '10000000-0000-0000-0000-000000000101',
  '10000000-0000-0000-0000-000000000201',
  '10000000-0000-0000-0000-000000000301',
  decode(repeat('11', 32), 'hex'),
  decode(repeat('22', 32), 'hex'),
  decode(repeat('33', 32), 'hex'),
  1,
  array['11111111111111111111111111111111']::text[],
  decode(repeat('44', 32), 'hex'),
  '2099-01-01T00:00:00Z'::timestamptz,
  'So11111111111111111111111111111111111111112',
  1,
  '11111111111111111111111111111111',
  'ELIGIBLE'
);

do $$
begin
  begin
    insert into app.settlement_operations(
      settlement_id,
      refresh_id,
      verification_result_id,
      operation_id,
      operation_hash,
      verification_digest,
      execution_hash,
      recipient_mask,
      recipient_wallets,
      chain_refresh_id,
      refresh_expires_at,
      reward_mint,
      locked_reward_atomic,
      chain_refresh_address,
      status
    ) values (
      '10000000-0000-0000-0000-000000000002',
      '10000000-0000-0000-0000-000000000101',
      '10000000-0000-0000-0000-000000000202',
      '10000000-0000-0000-0000-000000000302',
      decode(repeat('12', 32), 'hex'),
      decode(repeat('23', 32), 'hex'),
      decode(repeat('33', 32), 'hex'),
      1,
      array['11111111111111111111111111111111']::text[],
      decode(repeat('44', 32), 'hex'),
      '2099-01-01T00:00:00Z'::timestamptz,
      'So11111111111111111111111111111111111111112',
      1,
      '11111111111111111111111111111111',
      'ELIGIBLE'
    );
    raise exception 'second logical settlement for one refresh was accepted';
  exception
    when unique_violation then null;
  end;

  begin
    insert into app.settlement_operations(
      settlement_id,
      refresh_id,
      verification_result_id,
      operation_id,
      operation_hash,
      verification_digest,
      execution_hash,
      recipient_mask,
      recipient_wallets,
      chain_refresh_id,
      refresh_expires_at,
      reward_mint,
      locked_reward_atomic,
      chain_refresh_address,
      status
    ) values (
      '10000000-0000-0000-0000-000000000003',
      '10000000-0000-0000-0000-000000000102',
      '10000000-0000-0000-0000-000000000203',
      '10000000-0000-0000-0000-000000000303',
      decode(repeat('11', 32), 'hex'),
      decode(repeat('24', 32), 'hex'),
      decode(repeat('33', 32), 'hex'),
      1,
      array['11111111111111111111111111111111']::text[],
      decode(repeat('44', 32), 'hex'),
      '2099-01-01T00:00:00Z'::timestamptz,
      'So11111111111111111111111111111111111111112',
      1,
      '11111111111111111111111111111111',
      'ELIGIBLE'
    );
    raise exception 'replayed settlement operation hash was accepted';
  exception
    when unique_violation then null;
  end;

  begin
    insert into app.settlement_operations(
      settlement_id,
      refresh_id,
      verification_result_id,
      operation_id,
      operation_hash,
      verification_digest,
      execution_hash,
      recipient_mask,
      recipient_wallets,
      chain_refresh_id,
      refresh_expires_at,
      reward_mint,
      locked_reward_atomic,
      chain_refresh_address,
      status
    ) values (
      '10000000-0000-0000-0000-000000000004',
      '10000000-0000-0000-0000-000000000103',
      '10000000-0000-0000-0000-000000000204',
      '10000000-0000-0000-0000-000000000304',
      decode(repeat('00', 32), 'hex'),
      decode(repeat('25', 32), 'hex'),
      decode(repeat('33', 32), 'hex'),
      1,
      array['11111111111111111111111111111111']::text[],
      decode(repeat('44', 32), 'hex'),
      '2099-01-01T00:00:00Z'::timestamptz,
      'So11111111111111111111111111111111111111112',
      1,
      '11111111111111111111111111111111',
      'ELIGIBLE'
    );
    raise exception 'zero settlement operation hash was accepted';
  exception
    when check_violation then null;
  end;

  begin
    insert into app.settlement_operations(
      settlement_id,
      refresh_id,
      verification_result_id,
      operation_id,
      operation_hash,
      verification_digest,
      execution_hash,
      recipient_mask,
      recipient_wallets,
      chain_refresh_id,
      refresh_expires_at,
      reward_mint,
      locked_reward_atomic,
      chain_refresh_address,
      status
    ) values (
      '10000000-0000-0000-0000-000000000005',
      '10000000-0000-0000-0000-000000000104',
      '10000000-0000-0000-0000-000000000205',
      '10000000-0000-0000-0000-000000000305',
      decode(repeat('15', 32), 'hex'),
      decode(repeat('00', 32), 'hex'),
      decode(repeat('33', 32), 'hex'),
      1,
      array['11111111111111111111111111111111']::text[],
      decode(repeat('44', 32), 'hex'),
      '2099-01-01T00:00:00Z'::timestamptz,
      'So11111111111111111111111111111111111111112',
      1,
      '11111111111111111111111111111111',
      'ELIGIBLE'
    );
    raise exception 'zero verification digest was accepted';
  exception
    when check_violation then null;
  end;

  begin
    insert into app.settlement_operations(
      settlement_id,
      refresh_id,
      verification_result_id,
      operation_id,
      operation_hash,
      verification_digest,
      execution_hash,
      recipient_mask,
      recipient_wallets,
      chain_refresh_id,
      refresh_expires_at,
      reward_mint,
      locked_reward_atomic,
      chain_refresh_address,
      status
    ) values (
      '10000000-0000-0000-0000-000000000006',
      '10000000-0000-0000-0000-000000000105',
      '10000000-0000-0000-0000-000000000206',
      '10000000-0000-0000-0000-000000000306',
      decode(repeat('16', 31), 'hex'),
      decode(repeat('26', 32), 'hex'),
      decode(repeat('33', 32), 'hex'),
      1,
      array['11111111111111111111111111111111']::text[],
      decode(repeat('44', 32), 'hex'),
      '2099-01-01T00:00:00Z'::timestamptz,
      'So11111111111111111111111111111111111111112',
      1,
      '11111111111111111111111111111111',
      'ELIGIBLE'
    );
    raise exception 'short settlement operation hash was accepted';
  exception
    when check_violation then null;
  end;

  begin
    insert into app.settlement_operations(
      settlement_id,
      refresh_id,
      verification_result_id,
      operation_id,
      operation_hash,
      verification_digest,
      execution_hash,
      recipient_mask,
      recipient_wallets,
      chain_refresh_id,
      refresh_expires_at,
      reward_mint,
      locked_reward_atomic,
      chain_refresh_address,
      status
    ) values (
      '10000000-0000-0000-0000-000000000007',
      '10000000-0000-0000-0000-000000000106',
      '10000000-0000-0000-0000-000000000207',
      '10000000-0000-0000-0000-000000000307',
      decode(repeat('17', 32), 'hex'),
      decode(repeat('27', 31), 'hex'),
      decode(repeat('33', 32), 'hex'),
      1,
      array['11111111111111111111111111111111']::text[],
      decode(repeat('44', 32), 'hex'),
      '2099-01-01T00:00:00Z'::timestamptz,
      'So11111111111111111111111111111111111111112',
      1,
      '11111111111111111111111111111111',
      'ELIGIBLE'
    );
    raise exception 'short verification digest was accepted';
  exception
    when check_violation then null;
  end;
end
$$;

insert into app.refund_operations(
  refund_id,
  refresh_id,
  contribution_id,
  actor_id,
  operation_id,
  status
) values (
  '20000000-0000-0000-0000-000000000001',
  '20000000-0000-0000-0000-000000000101',
  '20000000-0000-0000-0000-000000000201',
  '20000000-0000-0000-0000-000000000301',
  '20000000-0000-0000-0000-000000000401',
  'ELIGIBLE'
);

do $$
begin
  begin
    insert into app.refund_operations(
      refund_id,
      refresh_id,
      contribution_id,
      actor_id,
      operation_id,
      status
    ) values (
      '20000000-0000-0000-0000-000000000002',
      '20000000-0000-0000-0000-000000000101',
      '20000000-0000-0000-0000-000000000201',
      '20000000-0000-0000-0000-000000000301',
      '20000000-0000-0000-0000-000000000402',
      'ELIGIBLE'
    );
    raise exception 'second logical refund for one contribution was accepted';
  exception
    when unique_violation then null;
  end;
end
$$;

rollback;
