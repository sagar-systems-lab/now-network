alter table app.evidence_challenges
  add constraint evidence_challenges_nonce_hash_sha256
    check (octet_length(nonce_hash) = 32),
  add constraint evidence_challenges_status_timestamps
    check (
      (
        status = 'ISSUED'
        and consumed_at is null
        and revoked_at is null
      )
      or (
        status = 'CONSUMED'
        and consumed_at is not null
        and revoked_at is null
        and consumed_at <= expires_at
      )
      or (
        status = 'EXPIRED'
        and consumed_at is null
        and revoked_at is null
      )
      or (
        status = 'REVOKED'
        and consumed_at is null
        and revoked_at is not null
        and revoked_at <= expires_at
      )
    );

create unique index evidence_challenges_nonce_hash_uq
  on app.evidence_challenges(nonce_hash);

create index evidence_challenges_expiry_idx
  on app.evidence_challenges(status, expires_at);
