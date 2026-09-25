\set ON_ERROR_STOP on

begin;

do $$
declare
  shape_constraint text;
  object_index text;
  issued_index text;
begin
  select pg_get_constraintdef(oid)
  into shape_constraint
  from pg_constraint
  where conrelid = 'app.evidence_challenges'::regclass
    and conname = 'evidence_challenges_upload_reservation_shape';

  if shape_constraint is null
     or position('upload_object_key' in lower(shape_constraint)) = 0
     or position('upload_mime' in lower(shape_constraint)) = 0
     or position('upload_issued_at' in lower(shape_constraint)) = 0
     or position('expires_at' in lower(shape_constraint)) = 0 then
    raise exception 'signed upload reservation constraint missing: %', shape_constraint;
  end if;

  select indexdef
  into object_index
  from pg_indexes
  where schemaname = 'app'
    and tablename = 'evidence_challenges'
    and indexname = 'evidence_challenges_upload_object_key_uq';

  if object_index is null
     or position('UNIQUE INDEX' in object_index) = 0
     or position('upload_object_key' in object_index) = 0 then
    raise exception 'signed upload object-key uniqueness missing: %', object_index;
  end if;

  select indexdef
  into issued_index
  from pg_indexes
  where schemaname = 'app'
    and tablename = 'evidence_challenges'
    and indexname = 'evidence_challenges_upload_issued_idx';

  if issued_index is null
     or position('upload_issued_at' in issued_index) = 0 then
    raise exception 'signed upload issuance index missing: %', issued_index;
  end if;
end
$$;

rollback;
