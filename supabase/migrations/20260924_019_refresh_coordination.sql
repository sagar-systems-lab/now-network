alter table app.refresh_requests
  add column coordinator_version smallint not null default 0
    check (coordinator_version in (0, 1)),
  add column creator_wallet_address text,
  add column funding_target_atomic numeric(20,0),
  add column payout_rule text,
  add column chain_refresh_id bytea,
  add column state_id_digest bytea;

alter table app.refresh_requests
  add constraint refresh_requests_coordinator_v1_shape
  check (
    coordinator_version = 0
    or (
      creator_wallet_address is not null
      and length(btrim(creator_wallet_address)) > 0
      and funding_target_atomic is not null
      and funding_target_atomic > 0
      and payout_rule in ('SINGLE_WINNER_ALL', 'EQUAL_SPLIT_REQUIRED_WITNESSES')
      and chain_refresh_id is not null
      and octet_length(chain_refresh_id) = 32
      and state_id_digest is not null
      and octet_length(state_id_digest) = 32
    )
  );

create unique index refresh_requests_chain_refresh_id_idx
  on app.refresh_requests(chain_refresh_id)
  where chain_refresh_id is not null;

create unique index refresh_requests_chain_refresh_address_idx
  on app.refresh_requests(chain_refresh_address)
  where chain_refresh_address is not null;

create unique index refresh_contributions_chain_signature_idx
  on app.refresh_contributions(chain_signature)
  where chain_signature is not null;

create index refresh_requests_requester_status_idx
  on app.refresh_requests(requester_actor_id, status, created_at desc);
