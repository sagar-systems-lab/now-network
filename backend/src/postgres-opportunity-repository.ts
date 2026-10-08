import postgres from "npm:postgres@3.4.7";
import type {
  NearbyOpportunityInput,
  OpportunityRecord,
  OpportunityRepository,
} from "./opportunity-repository.ts";

type DateLike = Date | string;

type OpportunityRow = {
  latitude: number;
  longitude: number;
  refresh_id: string;
  state_id: string;
  state_version: number | string;
  title: string;
  question: string;
  state_type: OpportunityRecord["stateType"];
  unit_code: string | null;
  location_id: string;
  location_name: string;
  location_type: string;
  display_address: string | null;
  reward_mint: string;
  reward_atomic: number | string;
  payout_rule: OpportunityRecord["payoutRule"];
  refresh_expires_at: DateLike;
  evidence_deadline: DateLike;
  verification_class: OpportunityRecord["verificationClass"];
  required_witnesses: number | string;
  max_witnesses: number | string;
  active_claims: number | string;
  actor_has_active_claim?: boolean;
  remaining_slots: number | string;
  proof_policy_snapshot: OpportunityRecord["proofPolicySnapshot"];
  distance_m: number | string | null;
  state_revision: number | string;
  refresh_revision: number | string;
};

function date(value: DateLike): Date {
  return value instanceof Date ? value : new Date(value);
}

function fromRow(row: OpportunityRow): OpportunityRecord {
  return {
    center: { latitude: Number(row.latitude), longitude: Number(row.longitude) },
    refreshId: row.refresh_id,
    stateId: row.state_id,
    stateVersion: Number(row.state_version),
    title: row.title,
    question: row.question,
    stateType: row.state_type,
    unitCode: row.unit_code,
    locationId: row.location_id,
    locationName: row.location_name,
    locationType: row.location_type,
    displayAddress: row.display_address,
    rewardMint: row.reward_mint,
    rewardAtomic: BigInt(row.reward_atomic),
    payoutRule: row.payout_rule,
    refreshExpiresAt: date(row.refresh_expires_at),
    evidenceDeadline: date(row.evidence_deadline),
    verificationClass: row.verification_class,
    requiredWitnesses: Number(row.required_witnesses),
    maxWitnesses: Number(row.max_witnesses),
    activeClaims: Number(row.active_claims),
    actorHasActiveClaim: row.actor_has_active_claim === true,
    remainingSlots: Number(row.remaining_slots),
    proofPolicySnapshot: row.proof_policy_snapshot,
    distanceM: row.distance_m === null ? null : Number(row.distance_m),
    stateRevision: Number(row.state_revision),
    refreshRevision: Number(row.refresh_revision),
  };
}

export class PostgresOpportunityRepository implements OpportunityRepository {
  private readonly sql: ReturnType<typeof postgres>;

  constructor(connectionString: string) {
    this.sql = postgres(connectionString, {
      max: 1,
      prepare: false,
      idle_timeout: 20,
      connect_timeout: 10,
    });
  }

  private candidates(input: NearbyOpportunityInput) {
    return this.sql`
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
        extensions.st_y(l.center::extensions.geometry) as latitude,
        extensions.st_x(l.center::extensions.geometry) as longitude,
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
        extensions.st_distance(l.center, extensions.st_setsrid(
          extensions.st_makepoint(${input.lng}, ${input.lat}), 4326
        )::extensions.geography) as distance_m,
        case when rr.payout_rule = 'EQUAL_SPLIT_REQUIRED_WITNESSES'
          then floor(coalesce(rr.chain_locked_reward, rr.chain_total_funded) / rr.required_witnesses)
          else coalesce(rr.chain_locked_reward, rr.chain_total_funded)
        end as estimated_atomic,
        coalesce(ls.revision, sd.revision) as state_revision,
        rr.revision as refresh_revision
      from app.refresh_requests rr
      join app.state_definitions sd
        on sd.state_id = rr.state_id
       and sd.version = rr.state_version
      join app.locations l on l.location_id = sd.location_id
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
      where extensions.st_dwithin(l.center, extensions.st_setsrid(
          extensions.st_makepoint(${input.lng}, ${input.lat}), 4326
        )::extensions.geography, ${input.radiusM})
        and rr.requester_actor_id <> ${input.actorId}::uuid
        and rr.status in ('AVAILABLE', 'ADDITIONAL_VERIFICATION')
        and rr.refresh_expires_at > ${input.asOf ?? new Date()}
        and sd.status = 'ACTIVE'
        and coalesce(rr.chain_locked_reward, rr.chain_total_funded) > 0
        and rr.payout_rule in ('SINGLE_WINNER_ALL', 'EQUAL_SPLIT_REQUIRED_WITNESSES')
        and coalesce(claims.active_claims, 0) < rr.max_witnesses
        and not exists (
          select 1
          from app.refresh_acceptances mine
          where mine.refresh_id = rr.refresh_id
            and mine.actor_id = ${input.actorId}::uuid
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
    `;
  }

  async listNearby(input: NearbyOpportunityInput): Promise<OpportunityRecord[]> {
    const sort = input.sort ?? "nearest";
    const cursor = input.cursor;
    const rows = await this.sql`
      with candidates as (${this.candidates(input)})
      select * from candidates
      where (${input.category ?? null}::text is null or location_type = ${input.category ?? null})
        and (${cursor === null} or
          (${sort} = 'nearest' and (distance_m, refresh_id) >
            (${cursor?.distanceM ?? null}::double precision, ${cursor?.refreshId ?? null}::uuid)) or
          (${sort} = 'ending' and (refresh_expires_at, refresh_id) >
            (${cursor?.expiresAt ?? null}::timestamptz, ${cursor?.refreshId ?? null}::uuid)) or
          (${sort} = 'payout' and (
            reward_mint collate "C" > ${cursor?.rewardMint ?? null} or
            (reward_mint = ${cursor?.rewardMint ?? null} and (
              estimated_atomic < ${cursor?.estimatedAtomic ?? null}::numeric or
              (estimated_atomic = ${cursor?.estimatedAtomic ?? null}::numeric
                and refresh_id > ${cursor?.refreshId ?? null}::uuid)
            ))
          ))
        )
      order by
        case when ${sort} = 'nearest' then distance_m end,
        case when ${sort} = 'ending' then refresh_expires_at end,
        case when ${sort} = 'payout' then reward_mint collate "C" end,
        case when ${sort} = 'payout' then estimated_atomic end desc,
        refresh_id
      limit ${input.limit}
    `;
    return rows.map((row) => fromRow(row as unknown as OpportunityRow));
  }

  async summarizeNearby(input: NearbyOpportunityInput) {
    const rows = await this.sql`
      with candidates as (${this.candidates(input)})
      select count(*) filter (
        where ${input.category ?? null}::text is null or location_type = ${input.category ?? null}
      )::integer as total,
      coalesce(jsonb_agg(distinct location_type order by location_type), '[]'::jsonb) as categories
      from candidates
    `;
    return { total: Number(rows[0].total), categories: rows[0].categories as string[] };
  }

  async close(): Promise<void> {
    await this.sql.end({ timeout: 1 });
  }

  async getOpportunity(
    input: Parameters<OpportunityRepository["getOpportunity"]>[0],
  ): Promise<OpportunityRecord | null> {
    const rows = await this.sql`
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
        extensions.st_y(l.center::extensions.geometry) as latitude,
        extensions.st_x(l.center::extensions.geometry) as longitude,
        rr.reward_mint,
        coalesce(rr.chain_locked_reward, rr.chain_total_funded) as reward_atomic,
        rr.payout_rule,
        rr.refresh_expires_at,
        rr.evidence_deadline,
        rr.verification_class,
        rr.required_witnesses,
        rr.max_witnesses,
        coalesce(claims.active_claims, 0)::integer as active_claims,
        coalesce(claims.actor_has_active_claim, false) as actor_has_active_claim,
        (rr.max_witnesses - coalesce(claims.active_claims, 0))::integer as remaining_slots,
        rr.proof_policy_snapshot,
        null::double precision as distance_m,
        coalesce(ls.revision, sd.revision) as state_revision,
        rr.revision as refresh_revision
      from app.refresh_requests rr
      join app.state_definitions sd
        on sd.state_id = rr.state_id
       and sd.version = rr.state_version
      join app.locations l on l.location_id = sd.location_id
      left join app.live_states ls on ls.state_id = sd.state_id
      left join lateral (
        select count(*)::integer as active_claims,
          bool_or(ra.actor_id = ${input.actorId}::uuid) as actor_has_active_claim
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
      where rr.refresh_id = ${input.refreshId}::uuid
        and rr.requester_actor_id <> ${input.actorId}::uuid
        and rr.refresh_expires_at > now()
        and sd.status = 'ACTIVE'
        and coalesce(rr.chain_locked_reward, rr.chain_total_funded) > 0
        and rr.payout_rule in ('SINGLE_WINNER_ALL', 'EQUAL_SPLIT_REQUIRED_WITNESSES')
        and (
          (
            rr.status in ('AVAILABLE', 'ADDITIONAL_VERIFICATION')
            and coalesce(claims.active_claims, 0) < rr.max_witnesses
            and not exists (
              select 1
              from app.refresh_acceptances mine
              where mine.refresh_id = rr.refresh_id
                and mine.actor_id = ${input.actorId}::uuid
                and mine.status in (
                  'PREPARING', 'WALLET_PENDING', 'SUBMITTED', 'CONFIRMING',
                  'CLAIMED', 'CAPTURE_ACTIVE', 'EVIDENCE_COMMITTED', 'RELEASE_ELIGIBLE', 'UNKNOWN'
                )
            )
          )
          or (
            rr.status in ('CLAIMED', 'CAPTURE_IN_PROGRESS', 'ADDITIONAL_VERIFICATION')
            and exists (
              select 1
              from app.refresh_acceptances mine
              where mine.refresh_id = rr.refresh_id
                and mine.actor_id = ${input.actorId}::uuid
                and mine.claim_slot is not null
                and mine.status in ('CLAIMED', 'CAPTURE_ACTIVE')
            )
          )
        )
      limit 1
    `;

    return rows[0] ? fromRow(rows[0] as unknown as OpportunityRow) : null;
  }
}
