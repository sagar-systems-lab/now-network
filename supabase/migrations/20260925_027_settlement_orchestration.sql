alter table app.settlement_operations
  add column execution_hash bytea,
  add column recipient_mask smallint,
  add column reward_mint text,
  add column locked_reward_atomic numeric(20,0),
  add column chain_refresh_address text;

update app.settlement_operations so
set
  execution_hash = vr.execution_hash,
  reward_mint = rr.reward_mint,
  locked_reward_atomic = rr.chain_locked_reward,
  chain_refresh_address = rr.chain_refresh_address
from app.verification_results vr,
     app.refresh_requests rr
where so.verification_result_id = vr.verification_result_id
  and so.refresh_id = rr.refresh_id;

do $$
begin
  if exists (
    select 1
    from app.settlement_operations
    where execution_hash is null
       or reward_mint is null
       or locked_reward_atomic is null
       or chain_refresh_address is null
  ) then
    raise exception 'existing settlement operation cannot be upgraded safely';
  end if;
end
$$;

alter table app.settlement_operations
  alter column execution_hash set not null,
  alter column reward_mint set not null,
  alter column locked_reward_atomic set not null,
  alter column chain_refresh_address set not null,
  add constraint settlement_operations_execution_hash_32_ck
    check (octet_length(execution_hash) = 32),
  add constraint settlement_operations_recipient_mask_ck
    check (recipient_mask is null or recipient_mask between 1 and 7),
  add constraint settlement_operations_locked_reward_ck
    check (
      locked_reward_atomic > 0
      and locked_reward_atomic <= 18446744073709551615
    ),
  add constraint settlement_operations_reward_mint_nonempty_ck
    check (length(btrim(reward_mint)) > 0),
  add constraint settlement_operations_refresh_address_nonempty_ck
    check (length(btrim(chain_refresh_address)) > 0),
  add constraint settlement_operations_attempt_shape_ck
    check (
      chain_signature is null
      or (
        recent_blockhash is not null
        and length(btrim(recent_blockhash)) > 0
        and last_valid_block_height is not null
        and last_valid_block_height >= 0
      )
    );

create unique index settlement_operations_verification_result_uq
  on app.settlement_operations(verification_result_id);

create index settlement_operations_reconcile_idx
  on app.settlement_operations(status, next_reconcile_at)
  where status in (
    'ELIGIBLE',
    'SUBMITTED',
    'VERIFYING',
    'CONFIRMED',
    'NOT_SETTLED'
  );
