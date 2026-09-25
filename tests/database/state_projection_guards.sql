\set ON_ERROR_STOP on

begin;

do $$
declare
  live_digest_constraint text;
  history_digest_constraint text;
  verification_index text;
  append_only_trigger_count integer;
begin
  select pg_get_constraintdef(oid)
  into live_digest_constraint
  from pg_constraint
  where conrelid = 'app.live_states'::regclass
    and conname = 'live_states_value_digest_length';

  if live_digest_constraint is null
     or position('current_value_digest' in lower(live_digest_constraint)) = 0
     or position('octet_length' in lower(live_digest_constraint)) = 0 then
    raise exception 'live-state digest guard missing: %', live_digest_constraint;
  end if;

  select pg_get_constraintdef(oid)
  into history_digest_constraint
  from pg_constraint
  where conrelid = 'app.state_history'::regclass
    and conname = 'state_history_value_digest_length';

  if history_digest_constraint is null
     or position('value_digest' in lower(history_digest_constraint)) = 0
     or position('octet_length' in lower(history_digest_constraint)) = 0 then
    raise exception 'state-history digest guard missing: %', history_digest_constraint;
  end if;

  select indexdef
  into verification_index
  from pg_indexes
  where schemaname = 'app'
    and tablename = 'state_history'
    and indexname = 'state_history_verification_result_uq';

  if verification_index is null
     or position('UNIQUE INDEX' in verification_index) = 0
     or position('verification_result_id' in verification_index) = 0 then
    raise exception 'state-history verification uniqueness missing: %', verification_index;
  end if;

  select count(*)
  into append_only_trigger_count
  from pg_trigger
  where tgrelid = 'app.state_history'::regclass
    and tgname = 'state_history_append_only'
    and not tgisinternal;

  if append_only_trigger_count <> 1 then
    raise exception 'state-history append-only trigger missing';
  end if;
end
$$;

rollback;
