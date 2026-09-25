alter table app.evidence_challenges
  drop constraint evidence_challenges_upload_reservation_shape;

alter table app.evidence_challenges
  add column reserved_evidence_id uuid,
  add constraint evidence_challenges_upload_reservation_shape
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
  add constraint evidence_packets_digest_lengths
    check (
      octet_length(intent_core_hash) = 32
      and octet_length(execution_hash) = 32
      and (media_sha256 is null or octet_length(media_sha256) = 32)
    );
