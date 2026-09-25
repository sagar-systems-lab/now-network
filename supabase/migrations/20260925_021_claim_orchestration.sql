alter table app.refresh_acceptances
  add column claim_duration_seconds bigint,
  add constraint refresh_acceptances_claim_duration_seconds_bounds
    check (
      claim_duration_seconds is null
      or claim_duration_seconds between 1 and 4294967295
    );

create unique index refresh_acceptances_chain_signature_uq
  on app.refresh_acceptances(chain_signature)
  where chain_signature is not null;

create index refresh_acceptances_active_reservation_idx
  on app.refresh_acceptances(refresh_id, status, accepted_at)
  where status in (
    'PREPARING',
    'WALLET_PENDING',
    'SUBMITTED',
    'CONFIRMING',
    'CLAIMED',
    'CAPTURE_ACTIVE',
    'EVIDENCE_COMMITTED',
    'RELEASE_ELIGIBLE',
    'UNKNOWN'
  );
