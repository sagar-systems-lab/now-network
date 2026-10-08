alter table app.evidence_challenges
  add column upload_authorization jsonb,
  add constraint evidence_challenges_upload_authorization_shape check (
    upload_authorization is null or (
      reserved_evidence_id is not null
      and jsonb_typeof(upload_authorization) = 'object'
      and upload_authorization ?& array['signedUrl', 'expiresAt']
      and jsonb_typeof(upload_authorization->'signedUrl') = 'string'
      and jsonb_typeof(upload_authorization->'expiresAt') = 'string'
      and (not upload_authorization ? 'videoSignedUrl'
        or jsonb_typeof(upload_authorization->'videoSignedUrl') = 'string')
    )
  );

comment on column app.evidence_challenges.upload_authorization is
  'Private, expiring upload credentials. Reuse for the same authenticated challenge; never publish in events or projections.';
