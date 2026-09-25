\set ON_ERROR_STOP on

begin;

do $$
declare
  duration_type text;
  bounds text;
  signature_index text;
  reservation_index text;
begin
  select data_type
  into duration_type
  from information_schema.columns
  where table_schema = 'app'
    and table_name = 'refresh_acceptances'
    and column_name = 'claim_duration_seconds';

  if duration_type <> 'bigint' then
    raise exception 'claim duration column is missing or not bigint: %', duration_type;
  end if;

  select pg_get_constraintdef(oid)
  into bounds
  from pg_constraint
  where conrelid = 'app.refresh_acceptances'::regclass
    and conname = 'refresh_acceptances_claim_duration_seconds_bounds';

  if bounds is null
     or position('4294967295' in bounds) = 0
     or position('claim_duration_seconds' in bounds) = 0 then
    raise exception 'claim duration bounds are missing or malformed: %', bounds;
  end if;

  select indexdef
  into signature_index
  from pg_indexes
  where schemaname = 'app'
    and tablename = 'refresh_acceptances'
    and indexname = 'refresh_acceptances_chain_signature_uq';

  if signature_index is null
     or position('UNIQUE INDEX' in signature_index) = 0
     or position('chain_signature IS NOT NULL' in signature_index) = 0 then
    raise exception 'claim signature uniqueness index is missing or malformed: %', signature_index;
  end if;

  select indexdef
  into reservation_index
  from pg_indexes
  where schemaname = 'app'
    and tablename = 'refresh_acceptances'
    and indexname = 'refresh_acceptances_active_reservation_idx';

  if reservation_index is null
     or position('refresh_id' in reservation_index) = 0
     or position('WALLET_PENDING' in reservation_index) = 0
     or position('UNKNOWN' in reservation_index) = 0 then
    raise exception 'active claim reservation index is missing or malformed: %', reservation_index;
  end if;
end
$$;

rollback;
