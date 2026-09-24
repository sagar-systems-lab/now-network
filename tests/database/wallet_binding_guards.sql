\set ON_ERROR_STOP on

insert into app.actors(actor_id, status)
values ('00000000-0000-4000-8000-000000000101', 'ACTIVE');

insert into app.actor_auth_principals(auth_user_id, actor_id, principal_type)
values (
  '00000000-0000-4000-8000-000000000201',
  '00000000-0000-4000-8000-000000000101',
  'SUPABASE_ANONYMOUS'
);

insert into app.wallet_binding_challenges(
  challenge_id,
  actor_id,
  auth_user_id,
  wallet_address,
  cluster,
  purpose,
  domain,
  message,
  message_sha256,
  nonce_hash,
  status,
  issued_at,
  expires_at
) values (
  '00000000-0000-4000-8000-000000000301',
  '00000000-0000-4000-8000-000000000101',
  '00000000-0000-4000-8000-000000000201',
  '11111111111111111111111111111111',
  'devnet',
  'wallet_binding',
  'NOW Network',
  'test-message',
  decode(repeat('11', 32), 'hex'),
  decode(repeat('22', 32), 'hex'),
  'ISSUED',
  now(),
  now() + interval '5 minutes'
);

do $$
begin
  begin
    insert into app.wallet_binding_challenges(
      challenge_id,
      actor_id,
      auth_user_id,
      wallet_address,
      cluster,
      purpose,
      domain,
      message,
      message_sha256,
      nonce_hash,
      status,
      issued_at,
      expires_at
    ) values (
      '00000000-0000-4000-8000-000000000302',
      '00000000-0000-4000-8000-000000000101',
      '00000000-0000-4000-8000-000000000201',
      '11111111111111111111111111111111',
      'devnet',
      'wallet_binding',
      'NOW Network',
      'test-message-2',
      decode(repeat('33', 32), 'hex'),
      decode(repeat('44', 32), 'hex'),
      'ISSUED',
      now(),
      now() + interval '5 minutes'
    );
    raise exception 'second active wallet-binding challenge unexpectedly succeeded';
  exception
    when unique_violation then null;
  end;
end
$$;

update app.wallet_binding_challenges
set status = 'REVOKED', revoked_at = now()
where challenge_id = '00000000-0000-4000-8000-000000000301';

insert into app.wallet_binding_challenges(
  challenge_id,
  actor_id,
  auth_user_id,
  wallet_address,
  cluster,
  purpose,
  domain,
  message,
  message_sha256,
  nonce_hash,
  status,
  issued_at,
  expires_at
) values (
  '00000000-0000-4000-8000-000000000303',
  '00000000-0000-4000-8000-000000000101',
  '00000000-0000-4000-8000-000000000201',
  '11111111111111111111111111111111',
  'devnet',
  'wallet_binding',
  'NOW Network',
  'test-message-3',
  decode(repeat('55', 32), 'hex'),
  decode(repeat('66', 32), 'hex'),
  'ISSUED',
  now(),
  now() + interval '5 minutes'
);

do $$
begin
  begin
    insert into app.wallet_binding_challenges(
      challenge_id,
      actor_id,
      auth_user_id,
      wallet_address,
      cluster,
      purpose,
      domain,
      message,
      message_sha256,
      nonce_hash,
      status,
      issued_at,
      expires_at
    ) values (
      '00000000-0000-4000-8000-000000000304',
      '00000000-0000-4000-8000-000000000101',
      '00000000-0000-4000-8000-000000000201',
      '11111111111111111111111111111111',
      'devnet',
      'claim',
      'NOW Network',
      'bad-purpose',
      decode(repeat('77', 32), 'hex'),
      decode(repeat('88', 32), 'hex'),
      'REVOKED',
      now(),
      now() + interval '5 minutes'
    );
    raise exception 'invalid wallet-binding purpose unexpectedly succeeded';
  exception
    when check_violation then null;
  end;
end
$$;
