alter table app.live_states
  add constraint live_states_value_digest_length
    check (
      current_value_digest is null
      or octet_length(current_value_digest) = 32
    );

alter table app.state_history
  add constraint state_history_value_digest_length
    check (
      value_digest is null
      or octet_length(value_digest) = 32
    );

create unique index state_history_verification_result_uq
  on app.state_history(verification_result_id)
  where verification_result_id is not null;

create trigger state_history_append_only
before update or delete on app.state_history
for each row execute function app.reject_append_only_mutation();
