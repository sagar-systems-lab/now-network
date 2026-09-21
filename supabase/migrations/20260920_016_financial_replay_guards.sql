alter table app.settlement_operations
  add constraint settlement_operations_refresh_once_uk unique (refresh_id),
  add constraint settlement_operations_operation_hash_32_ck
    check (octet_length(operation_hash) = 32),
  add constraint settlement_operations_operation_hash_nonzero_ck
    check (operation_hash <> decode(repeat('00', 32), 'hex')),
  add constraint settlement_operations_verification_digest_32_ck
    check (octet_length(verification_digest) = 32),
  add constraint settlement_operations_verification_digest_nonzero_ck
    check (verification_digest <> decode(repeat('00', 32), 'hex'));

create unique index settlement_operations_operation_hash_uk
  on app.settlement_operations(operation_hash);

alter table app.refund_operations
  add constraint refund_operations_contribution_once_uk unique (contribution_id);
