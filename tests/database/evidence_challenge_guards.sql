\set ON_ERROR_STOP on

begin;

do $$
declare
  nonce_constraint text;
  status_constraint text;
  nonce_index text;
  expiry_index text;
  active_index text;
begin
  select pg_get_constraintdef(oid)
  into nonce_constraint
  from pg_constraint
  where conrelid = 'app.evidence_challenges'::regclass
    and conname = 'evidence_challenges_nonce_hash_sha256';

  if nonce_constraint is null
     or position('octet_length(nonce_hash) = 32' in lower(nonce_constraint)) = 0 then
    raise exception 'evidence challenge nonce hash constraint missing: %', nonce_constraint;
  end if;

  select pg_get_constraintdef(oid)
  into status_constraint
  from pg_constraint
  where conrelid = 'app.evidence_challenges'::regclass
    and conname = 'evidence_challenges_status_timestamps';

  if status_constraint is null
     or position('consumed_at' in lower(status_constraint)) = 0
     or position('revoked_at' in lower(status_constraint)) = 0
     or position('expires_at' in lower(status_constraint)) = 0 then
    raise exception 'evidence challenge status timestamp constraint missing: %', status_constraint;
  end if;

  select indexdef
  into nonce_index
  from pg_indexes
  where schemaname = 'app'
    and tablename = 'evidence_challenges'
    and indexname = 'evidence_challenges_nonce_hash_uq';

  if nonce_index is null
     or position('UNIQUE INDEX' in nonce_index) = 0
     or position('nonce_hash' in nonce_index) = 0 then
    raise exception 'evidence challenge nonce uniqueness index missing: %', nonce_index;
  end if;

  select indexdef
  into expiry_index
  from pg_indexes
  where schemaname = 'app'
    and tablename = 'evidence_challenges'
    and indexname = 'evidence_challenges_expiry_idx';

  if expiry_index is null
     or position('status' in expiry_index) = 0
     or position('expires_at' in expiry_index) = 0 then
    raise exception 'evidence challenge expiry index missing: %', expiry_index;
  end if;

  select indexdef
  into active_index
  from pg_indexes
  where schemaname = 'app'
    and tablename = 'evidence_challenges'
    and indexname = 'evidence_challenges_one_issued_per_acceptance';

  if active_index is null
     or position('UNIQUE INDEX' in active_index) = 0
     or position('status = ''ISSUED''' in active_index) = 0 then
    raise exception 'one active challenge per acceptance guard missing: %', active_index;
  end if;
end
$$;

rollback;
