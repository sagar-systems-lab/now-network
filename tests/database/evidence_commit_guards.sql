\set ON_ERROR_STOP on

begin;

do $$
declare
  reservation_constraint text;
  digest_constraint text;
  reserved_index text;
  commit_index text;
begin
  select pg_get_constraintdef(oid)
  into reservation_constraint
  from pg_constraint
  where conrelid = 'app.evidence_challenges'::regclass
    and conname = 'evidence_challenges_reserved_evidence_shape';

  if reservation_constraint is null
     or position('reserved_evidence_id' in lower(reservation_constraint)) = 0
     or position('upload_object_key' in lower(reservation_constraint)) = 0
     or position('upload_issued_at' in lower(reservation_constraint)) = 0 then
    raise exception 'reserved evidence identity guard missing: %', reservation_constraint;
  end if;

  select pg_get_constraintdef(oid)
  into digest_constraint
  from pg_constraint
  where conrelid = 'app.evidence_packets'::regclass
    and conname = 'evidence_packets_digest_lengths';

  if digest_constraint is null
     or position('intent_core_hash' in lower(digest_constraint)) = 0
     or position('execution_hash' in lower(digest_constraint)) = 0
     or position('media_sha256' in lower(digest_constraint)) = 0
     or position('commit_request_hash' in lower(digest_constraint)) = 0 then
    raise exception 'evidence digest length guard missing: %', digest_constraint;
  end if;

  select indexdef
  into reserved_index
  from pg_indexes
  where schemaname = 'app'
    and tablename = 'evidence_challenges'
    and indexname = 'evidence_challenges_reserved_evidence_id_uq';

  if reserved_index is null
     or position('UNIQUE INDEX' in reserved_index) = 0
     or position('reserved_evidence_id' in reserved_index) = 0 then
    raise exception 'reserved evidence identity uniqueness missing: %', reserved_index;
  end if;

  select indexdef
  into commit_index
  from pg_indexes
  where schemaname = 'app'
    and tablename = 'evidence_packets'
    and indexname = 'evidence_packets_commit_request_actor_uq';

  if commit_index is null
     or position('UNIQUE INDEX' in commit_index) = 0
     or position('commit_request_hash' in commit_index) = 0 then
    raise exception 'evidence commit request uniqueness missing: %', commit_index;
  end if;
end
$$;

rollback;
