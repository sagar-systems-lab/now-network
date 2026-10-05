alter table app.state_definitions add column ask_question_key text not null default '';
alter table app.state_definitions add constraint state_custom_question_policy check (
  ask_question_key = '' or (
    (policy_template_key = 'visual.current_condition.v1') is true
    and ask_question_key ~ '^[0-9a-f]{64}$'
  )
);

drop index app.state_location_policy_version_idx;
create unique index state_location_policy_version_idx
  on app.state_definitions(location_id, policy_template_key, ask_question_key, version)
  where policy_template_key is not null;
