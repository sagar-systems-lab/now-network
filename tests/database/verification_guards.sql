\set ON_ERROR_STOP on

begin;

do $$
declare
  digest_constraint text;
  terminal_constraint text;
  digest_index text;
begin
  select pg_get_constraintdef(oid)
  into digest_constraint
  from pg_constraint
  where conrelid = 'app.verification_results'::regclass
    and conname = 'verification_results_digest_lengths';

  if digest_constraint is null
     or position('execution_hash' in lower(digest_constraint)) = 0
     or position('canonical_digest' in lower(digest_constraint)) = 0
     or position('octet_length' in lower(digest_constraint)) = 0 then
    raise exception 'verification digest guard missing: %', digest_constraint;
  end if;

  select pg_get_constraintdef(oid)
  into terminal_constraint
  from pg_constraint
  where conrelid = 'app.verification_results'::regclass
    and conname = 'verification_results_terminal_timestamps';

  if terminal_constraint is null
     or position('completed_at' in lower(terminal_constraint)) = 0
     or position('started_at' in lower(terminal_constraint)) = 0
     or position('verified' in lower(terminal_constraint)) = 0
     or position('conflict' in lower(terminal_constraint)) = 0 then
    raise exception 'verification terminal timestamp guard missing: %', terminal_constraint;
  end if;

  select indexdef
  into digest_index
  from pg_indexes
  where schemaname = 'app'
    and tablename = 'verification_results'
    and indexname = 'verification_results_canonical_digest_uq';

  if digest_index is null
     or position('UNIQUE INDEX' in digest_index) = 0
     or position('canonical_digest' in digest_index) = 0 then
    raise exception 'verification canonical digest uniqueness missing: %', digest_index;
  end if;
end
$$;

rollback;
