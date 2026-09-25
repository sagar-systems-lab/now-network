alter table app.evidence_challenges
  add column upload_object_key text,
  add column upload_mime text,
  add column upload_issued_at timestamptz,
  add constraint evidence_challenges_upload_reservation_shape
    check (
      (
        upload_object_key is null
        and upload_mime is null
        and upload_issued_at is null
      )
      or (
        upload_object_key is not null
        and length(btrim(upload_object_key)) > 0
        and upload_mime is not null
        and length(btrim(upload_mime)) between 3 and 128
        and upload_issued_at is not null
        and upload_issued_at >= issued_at
        and upload_issued_at < expires_at
      )
    );

create unique index evidence_challenges_upload_object_key_uq
  on app.evidence_challenges(upload_object_key)
  where upload_object_key is not null;

create index evidence_challenges_upload_issued_idx
  on app.evidence_challenges(upload_issued_at)
  where upload_issued_at is not null;
