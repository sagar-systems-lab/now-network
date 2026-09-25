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

create or replace function app.query_nearby_opportunities_v1(
  p_actor_id uuid,
  p_lat double precision,
  p_lng double precision,
  p_radius_m integer,
  p_limit integer,
  p_after_distance_m double precision default null,
  p_after_refresh_id uuid default null
)
returns table (
  refresh_id uuid,
  state_id uuid,
  state_version integer,
  title text,
  question text,
  state_type app.state_type,
  unit_code text,
  location_id uuid,
  location_name text,
  location_type text,
  display_address text,
  reward_mint text,
  reward_atomic numeric,
  payout_rule text,
  refresh_expires_at timestamptz,
  evidence_deadline timestamptz,
  verification_class app.verification_class,
  required_witnesses smallint,
  max_witnesses smallint,
  active_claims integer,
  remaining_slots integer,
  proof_policy_snapshot jsonb,
  distance_m double precision,
  state_revision bigint,
  refresh_revision bigint
)
language sql
stable
set search_path = pg_catalog, app, extensions
as $$
  with query_point as (
    select extensions.st_setsrid(
      extensions.st_makepoint(p_lng, p_lat),
      4326
    )::extensions.geography as point
  ),
  candidates as (
    select
      rr.refresh_id,
      rr.state_id,
      rr.state_version,
      sd.title,
      sd.question,
      sd.state_type,
      sd.unit_code,
      l.location_id,
      l.name as location_name,
      l.location_type,
      l.display_address,
      rr.reward_mint,
      coalesce(rr.chain_locked_reward, rr.chain_total_funded) as reward_atomic,
      rr.payout_rule,
      rr.refresh_expires_at,
      rr.evidence_deadline,
      rr.verification_class,
      rr.required_witnesses,
      rr.max_witnesses,
      coalesce(claims.active_claims, 0)::integer as active_claims,
      (rr.max_witnesses - coalesce(claims.active_claims, 0))::integer as remaining_slots,
      rr.proof_policy_snapshot,
      extensions.st_distance(l.center, qp.point) as distance_m,
      coalesce(ls.revision, sd.revision) as state_revision,
      rr.revision as refresh_revision
    from app.refresh_requests rr
    join app.state_definitions sd
      on sd.state_id = rr.state_id
     and sd.version = rr.state_version
    join app.locations l on l.location_id = sd.location_id
    cross join query_point qp
    left join app.live_states ls on ls.state_id = sd.state_id
    left join lateral (
      select count(*)::integer as active_claims
      from app.refresh_acceptances ra
      where ra.refresh_id = rr.refresh_id
        and ra.claim_slot is not null
        and ra.status in (
          'PREPARING',
          'WALLET_PENDING',
          'SUBMITTED',
          'CONFIRMING',
          'CLAIMED',
          'CAPTURE_ACTIVE',
          'EVIDENCE_COMMITTED',
          'RELEASE_ELIGIBLE',
          'UNKNOWN'
        )
    ) claims on true
    where p_actor_id is not null
      and p_lat between -90 and 90
      and p_lng between -180 and 180
      and p_radius_m between 1 and 50000
      and p_limit between 1 and 51
      and rr.requester_actor_id <> p_actor_id
      and rr.status in ('AVAILABLE', 'ADDITIONAL_VERIFICATION')
      and rr.refresh_expires_at > now()
      and sd.status = 'ACTIVE'
      and coalesce(rr.chain_locked_reward, rr.chain_total_funded) > 0
      and rr.payout_rule in ('SINGLE_WINNER_ALL', 'EQUAL_SPLIT_REQUIRED_WITNESSES')
      and coalesce(claims.active_claims, 0) < rr.max_witnesses
      and extensions.st_dwithin(l.center, qp.point, p_radius_m)
      and not exists (
        select 1
        from app.refresh_acceptances mine
        where mine.refresh_id = rr.refresh_id
          and mine.actor_id = p_actor_id
          and mine.status in (
            'PREPARING',
            'WALLET_PENDING',
            'SUBMITTED',
            'CONFIRMING',
            'CLAIMED',
            'CAPTURE_ACTIVE',
            'EVIDENCE_COMMITTED',
            'RELEASE_ELIGIBLE',
            'UNKNOWN'
          )
      )
  )
  select
    candidates.refresh_id,
    candidates.state_id,
    candidates.state_version,
    candidates.title,
    candidates.question,
    candidates.state_type,
    candidates.unit_code,
    candidates.location_id,
    candidates.location_name,
    candidates.location_type,
    candidates.display_address,
    candidates.reward_mint,
    candidates.reward_atomic,
    candidates.payout_rule,
    candidates.refresh_expires_at,
    candidates.evidence_deadline,
    candidates.verification_class,
    candidates.required_witnesses,
    candidates.max_witnesses,
    candidates.active_claims,
    candidates.remaining_slots,
    candidates.proof_policy_snapshot,
    candidates.distance_m,
    candidates.state_revision,
    candidates.refresh_revision
  from candidates
  where
    (
      p_after_distance_m is null
      and p_after_refresh_id is null
    )
    or
    (
      p_after_distance_m is not null
      and p_after_refresh_id is not null
      and (
        candidates.distance_m > p_after_distance_m
        or (
          candidates.distance_m = p_after_distance_m
          and candidates.refresh_id > p_after_refresh_id
        )
      )
    )
  order by candidates.distance_m, candidates.refresh_id
  limit least(p_limit, 51);
$$;

revoke all on function app.query_nearby_opportunities_v1(
  uuid,
  double precision,
  double precision,
  integer,
  integer,
  double precision,
  uuid
) from public;
