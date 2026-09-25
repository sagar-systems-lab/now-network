\set ON_ERROR_STOP on

begin;

do $$
declare
  operation_hash_constraint text;
  execution_hash_constraint text;
  recipient_mask_constraint text;
  recipient_wallets_constraint text;
  chain_refresh_constraint text;
  verification_index text;
  reconcile_index text;
  signature_index text;
begin
  select pg_get_constraintdef(oid)
  into operation_hash_constraint
  from pg_constraint
  where conrelid = 'app.settlement_operations'::regclass
    and conname = 'settlement_operations_operation_hash_32_ck';

  if operation_hash_constraint is null
     or position('octet_length' in lower(operation_hash_constraint)) = 0 then
    raise exception 'settlement operation hash guard missing';
  end if;

  select pg_get_constraintdef(oid)
  into execution_hash_constraint
  from pg_constraint
  where conrelid = 'app.settlement_operations'::regclass
    and conname = 'settlement_operations_execution_hash_32_ck';

  if execution_hash_constraint is null
     or position('execution_hash' in lower(execution_hash_constraint)) = 0 then
    raise exception 'settlement execution hash guard missing';
  end if;

  select pg_get_constraintdef(oid)
  into recipient_mask_constraint
  from pg_constraint
  where conrelid = 'app.settlement_operations'::regclass
    and conname = 'settlement_operations_recipient_mask_ck';

  if recipient_mask_constraint is null
     or position('recipient_mask' in lower(recipient_mask_constraint)) = 0 then
    raise exception 'settlement recipient mask guard missing';
  end if;

  select pg_get_constraintdef(oid)
  into recipient_wallets_constraint
  from pg_constraint
  where conrelid = 'app.settlement_operations'::regclass
    and conname = 'settlement_operations_recipient_wallets_ck';

  if recipient_wallets_constraint is null
     or position('recipient_wallets' in lower(recipient_wallets_constraint)) = 0 then
    raise exception 'settlement recipient wallet guard missing';
  end if;

  select pg_get_constraintdef(oid)
  into chain_refresh_constraint
  from pg_constraint
  where conrelid = 'app.settlement_operations'::regclass
    and conname = 'settlement_operations_chain_refresh_id_32_ck';

  if chain_refresh_constraint is null
     or position('chain_refresh_id' in lower(chain_refresh_constraint)) = 0 then
    raise exception 'settlement chain refresh identity guard missing';
  end if;

  select indexdef
  into verification_index
  from pg_indexes
  where schemaname = 'app'
    and tablename = 'settlement_operations'
    and indexname = 'settlement_operations_verification_result_uq';

  if verification_index is null
     or position('UNIQUE INDEX' in verification_index) = 0 then
    raise exception 'settlement verification uniqueness missing';
  end if;

  select indexdef
  into reconcile_index
  from pg_indexes
  where schemaname = 'app'
    and tablename = 'settlement_operations'
    and indexname = 'settlement_operations_reconcile_idx';

  if reconcile_index is null
     or position('NOT_SETTLED' in reconcile_index) = 0
     or position('VERIFYING' in reconcile_index) = 0 then
    raise exception 'settlement reconciliation index missing';
  end if;

  select indexdef
  into signature_index
  from pg_indexes
  where schemaname = 'app'
    and tablename = 'settlement_operations'
    and indexname = 'settlement_operations_chain_signature_uq';

  if signature_index is null
     or position('UNIQUE INDEX' in signature_index) = 0
     or position('chain_signature' in signature_index) = 0 then
    raise exception 'settlement signature uniqueness missing';
  end if;
end
$$;

rollback;
