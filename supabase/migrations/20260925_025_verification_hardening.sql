alter table app.verification_results
  add constraint verification_results_digest_lengths
    check (
      octet_length(execution_hash) = 32
      and octet_length(canonical_digest) = 32
    ),
  add constraint verification_results_terminal_timestamps
    check (
      status not in (
        'WAITING_FOR_MORE_EVIDENCE',
        'VERIFIED',
        'REJECTED',
        'CONFLICT',
        'EXPIRED'
      )
      or (
        started_at is not null
        and completed_at is not null
        and completed_at >= started_at
      )
    );

create unique index verification_results_canonical_digest_uq
  on app.verification_results(canonical_digest);
