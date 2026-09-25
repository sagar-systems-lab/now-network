alter table app.evidence_challenges
  add column reserved_evidence_id uuid;

update app.evidence_challenges
set reserved_evidence_id = split_part(upload_object_key, '/', 5)::uuid
where upload_object_key is not null
  and reserved_evidence_id is null
  and upload_object_key ~* '^evidence/[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}/[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}/[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}/[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$';

do $$
begin
  if exists (
    select 1
    from app.evidence_challenges
    where upload_object_key is not null
      and reserved_evidence_id is null
  ) then
    raise exception 'existing evidence upload reservation cannot be assigned an evidence id';
  end if;
end
$$;

alter table app.evidence_challenges
  add constraint evidence_challenges_reserved_evidence_shape
    check (
      (
        reserved_evidence_id is null
        and upload_object_key is null
        and upload_mime is null
        and upload_issued_at is null
      )
      or (
        reserved_evidence_id is not null
        and upload_object_key is not null
        and length(btrim(upload_object_key)) > 0
        and upload_mime is not null
        and length(btrim(upload_mime)) between 3 and 128
        and upload_issued_at is not null
        and upload_issued_at >= issued_at
        and upload_issued_at < expires_at
      )
    );

create unique index evidence_challenges_reserved_evidence_id_uq
  on app.evidence_challenges(reserved_evidence_id)
  where reserved_evidence_id is not null;

alter table app.evidence_packets
  add column commit_request_hash bytea,
  add constraint evidence_packets_digest_lengths
    check (
      octet_length(intent_core_hash) = 32
      and octet_length(execution_hash) = 32
      and (media_sha256 is null or octet_length(media_sha256) = 32)
      and (commit_request_hash is null or octet_length(commit_request_hash) = 32)
    );

create unique index evidence_packets_commit_request_actor_uq
  on app.evidence_packets(actor_id, commit_request_hash)
  where commit_request_hash is not null;
