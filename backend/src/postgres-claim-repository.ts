import postgres from "npm:postgres@3.4.7";
import type {
  ClaimObservationResult,
  ClaimRecord,
  ClaimRefreshContext,
  ClaimReplayLookup,
  ClaimRepository,
  ClaimWithRefresh,
  PrepareClaimResult,
} from "./claim-repository.ts";

type DateLike = Date | string;

type ClaimRow = {
  acceptance_id: string;
  refresh_id: string;
  actor_id: string;
  wallet_address: string;
  claim_slot: number | string | null;
  claim_duration_seconds: number | string | null;
  claim_deadline: DateLike | null;
  chain_signature: string | null;
  chain_status: string | null;
  claim_status: ClaimRecord["status"];
  accepted_at: DateLike;
  released_at: DateLike | null;
  claim_revision: number | string;
  requester_actor_id: string;
  refresh_status: ClaimRefreshContext["status"];
  required_witnesses: number | string;
  max_witnesses: number | string;
  refresh_expires_at: DateLike;
  evidence_deadline: DateLike;
  reward_mint: string;
  creator_wallet_address: string;
  chain_refresh_id: Uint8Array;
  state_id_digest: Uint8Array;
  intent_core_hash: Uint8Array;
  chain_refresh_address: string | null;
  chain_total_funded: number | string;
  chain_locked_reward: number | string | null;
  refresh_revision: number | string;
};

type RefreshRow = {
  refresh_id: string;
  requester_actor_id: string;
  refresh_status: ClaimRefreshContext["status"];
  required_witnesses: number | string;
  max_witnesses: number | string;
  refresh_expires_at: DateLike;
  evidence_deadline: DateLike;
  reward_mint: string;
  creator_wallet_address: string;
  chain_refresh_id: Uint8Array;
  state_id_digest: Uint8Array;
  intent_core_hash: Uint8Array;
  chain_refresh_address: string | null;
  chain_total_funded: number | string;
  chain_locked_reward: number | string | null;
  refresh_revision: number | string;
};

type IdempotencyRow = {
  actor_id: string | null;
  operation_type: string;
  request_hash: Uint8Array;
  operation_id: string | null;
};

const ACTIVE_CLAIM_STATUSES = [
  "PREPARING",
  "WALLET_PENDING",
  "SUBMITTED",
  "CONFIRMING",
  "CLAIMED",
  "CAPTURE_ACTIVE",
  "EVIDENCE_COMMITTED",
  "RELEASE_ELIGIBLE",
  "UNKNOWN",
] as const;

type ActiveClaimStatus = (typeof ACTIVE_CLAIM_STATUSES)[number];

function isActiveClaimStatus(status: ClaimRecord["status"]): status is ActiveClaimStatus {
  return (ACTIVE_CLAIM_STATUSES as readonly ClaimRecord["status"][]).includes(status);
}

const SELECT_REFRESH = String.raw`
  select
    refresh_id,
    requester_actor_id,
    status as refresh_status,
    required_witnesses,
    max_witnesses,
    refresh_expires_at,
    evidence_deadline,
    reward_mint,
    creator_wallet_address,
    chain_refresh_id,
    state_id_digest,
    intent_core_hash,
    chain_refresh_address,
    chain_total_funded,
    chain_locked_reward,
    revision as refresh_revision
  from app.refresh_requests
`;

const SELECT_CLAIM = String.raw`
  select
    ra.acceptance_id,
    ra.refresh_id,
    ra.actor_id,
    ra.wallet_address,
    ra.claim_slot,
    ra.claim_duration_seconds,
    ra.claim_deadline,
    ra.chain_signature,
    ra.chain_status,
    ra.status as claim_status,
    ra.accepted_at,
    ra.released_at,
    ra.revision as claim_revision,
    rr.requester_actor_id,
    rr.status as refresh_status,
    rr.required_witnesses,
    rr.max_witnesses,
    rr.refresh_expires_at,
    rr.evidence_deadline,
    rr.reward_mint,
    rr.creator_wallet_address,
    rr.chain_refresh_id,
    rr.state_id_digest,
    rr.intent_core_hash,
    rr.chain_refresh_address,
    rr.chain_total_funded,
    rr.chain_locked_reward,
    rr.revision as refresh_revision
  from app.refresh_acceptances ra
  join app.refresh_requests rr
    on rr.refresh_id = ra.refresh_id
   and rr.coordinator_version = 1
`;

function date(value: DateLike): Date {
  return value instanceof Date ? value : new Date(value);
}

function optionalDate(value: DateLike | null): Date | null {
  return value === null ? null : date(value);
}

function refreshFromRow(row: ClaimRow | RefreshRow): ClaimRefreshContext {
  return {
    refreshId: row.refresh_id,
    requesterActorId: row.requester_actor_id,
    status: row.refresh_status,
    requiredWitnesses: Number(row.required_witnesses),
    maxWitnesses: Number(row.max_witnesses),
    refreshExpiresAt: date(row.refresh_expires_at),
    evidenceDeadline: date(row.evidence_deadline),
    rewardMint: row.reward_mint,
    creatorWalletAddress: row.creator_wallet_address,
    chainRefreshId: new Uint8Array(row.chain_refresh_id),
    stateIdDigest: new Uint8Array(row.state_id_digest),
    intentCoreHash: new Uint8Array(row.intent_core_hash),
    chainRefreshAddress: row.chain_refresh_address,
    chainTotalFunded: BigInt(row.chain_total_funded),
    chainLockedReward: row.chain_locked_reward === null ? null : BigInt(row.chain_locked_reward),
    revision: Number(row.refresh_revision),
  };
}

function claimFromRow(row: ClaimRow): ClaimRecord {
  return {
    acceptanceId: row.acceptance_id,
    refreshId: row.refresh_id,
    actorId: row.actor_id,
    walletAddress: row.wallet_address,
    claimSlot: row.claim_slot === null ? null : Number(row.claim_slot),
    claimDurationSeconds: row.claim_duration_seconds === null
      ? null
      : Number(row.claim_duration_seconds),
    claimDeadline: optionalDate(row.claim_deadline),
    chainSignature: row.chain_signature,
    chainStatus: row.chain_status,
    status: row.claim_status,
    acceptedAt: date(row.accepted_at),
    releasedAt: optionalDate(row.released_at),
    revision: Number(row.claim_revision),
  };
}

function withRefresh(row: ClaimRow): ClaimWithRefresh {
  return {
    claim: claimFromRow(row),
    refresh: refreshFromRow(row),
  };
}

function bytesEqual(left: Uint8Array, right: Uint8Array): boolean {
  if (left.length !== right.length) return false;
  for (let index = 0; index < left.length; index += 1) {
    if (left[index] !== right[index]) return false;
  }
  return true;
}

function idempotencyMatches(
  row: IdempotencyRow,
  actorId: string,
  requestHash: Uint8Array,
): boolean {
  return row.actor_id === actorId &&
    row.operation_type === "CLAIM_PREPARE_V1" &&
    row.operation_id !== null &&
    bytesEqual(new Uint8Array(row.request_hash), requestHash);
}

export class PostgresClaimRepository implements ClaimRepository {
  private readonly sql: ReturnType<typeof postgres>;

  constructor(connectionString: string) {
    this.sql = postgres(connectionString, {
      max: 1,
      prepare: false,
      idle_timeout: 20,
      connect_timeout: 10,
    });
  }

  async lookupPrepareReplay(
    input: Parameters<ClaimRepository["lookupPrepareReplay"]>[0],
  ): Promise<ClaimReplayLookup> {
    const storedKey = `${input.actorId}:claim:prepare:v1:${input.idempotencyKey}`;
    const rows = await this.sql`
      select actor_id, operation_type, request_hash, operation_id
      from app.idempotency_records
      where idempotency_key = ${storedKey}
      limit 1
    `;
    if (!rows[0]) return { kind: "none" };

    const idempotency = rows[0] as IdempotencyRow;
    if (!idempotencyMatches(idempotency, input.actorId, input.requestHash)) {
      return { kind: "idempotency_conflict" };
    }

    const claimRows = await this.sql.unsafe(
      `${SELECT_CLAIM} where ra.acceptance_id = $1::uuid limit 1`,
      [idempotency.operation_id],
    );
    if (!claimRows[0]) {
      throw new Error("claim idempotency record references a missing acceptance");
    }

    return {
      kind: "replayed",
      ...withRefresh(claimRows[0] as unknown as ClaimRow),
    };
  }

  async prepareClaim(
    input: Parameters<ClaimRepository["prepareClaim"]>[0],
  ): Promise<PrepareClaimResult> {
    return await this.sql.begin(async (tx) => {
      const storedKey = `${input.actorId}:claim:prepare:v1:${input.idempotencyKey}`;
      await tx`select pg_advisory_xact_lock(hashtextextended(${storedKey}, 21))`;
      await tx`select pg_advisory_xact_lock(hashtextextended(${input.refreshId}, 22))`;

      const refreshRows = await tx.unsafe(
        `${SELECT_REFRESH} where refresh_id = $1::uuid and coordinator_version = 1 for update`,
        [input.refreshId],
      );
      if (!refreshRows[0]) return { kind: "not_found" } as const;
      const refresh = refreshFromRow(refreshRows[0] as unknown as RefreshRow);

      const replayRows = await tx`
        select actor_id, operation_type, request_hash, operation_id
        from app.idempotency_records
        where idempotency_key = ${storedKey}
        for update
      `;
      if (replayRows[0]) {
        const idempotency = replayRows[0] as IdempotencyRow;
        if (!idempotencyMatches(idempotency, input.actorId, input.requestHash)) {
          return { kind: "idempotency_conflict" } as const;
        }
        const claimRows = await tx.unsafe(
          `${SELECT_CLAIM} where ra.acceptance_id = $1::uuid limit 1`,
          [idempotency.operation_id],
        );
        if (!claimRows[0]) {
          throw new Error("claim idempotency acceptance is missing");
        }
        return {
          kind: "replayed",
          ...withRefresh(claimRows[0] as unknown as ClaimRow),
        } as const;
      }

      if (refresh.requesterActorId === input.actorId) {
        return { kind: "self_claim" } as const;
      }
      const latestAllowedClaimTime = input.observedAt.getTime() +
        input.claimDurationSeconds * 1000;
      if (
        refresh.refreshExpiresAt.getTime() <= input.observedAt.getTime() ||
        refresh.evidenceDeadline.getTime() <= input.observedAt.getTime() ||
        latestAllowedClaimTime > refresh.evidenceDeadline.getTime()
      ) {
        return { kind: "expired" } as const;
      }
      if (
        (refresh.status !== "AVAILABLE" &&
          refresh.status !== "ADDITIONAL_VERIFICATION") ||
        refresh.chainRefreshAddress === null ||
        refresh.chainTotalFunded <= 0n
      ) {
        return { kind: "not_claimable" } as const;
      }

      const existingRows = await tx`
        select
          acceptance_id,
          wallet_address,
          status
        from app.refresh_acceptances
        where refresh_id = ${input.refreshId}::uuid
          and actor_id = ${input.actorId}::uuid
        for update
      `;

      let acceptanceId = input.acceptanceId;
      let responseCode = 201;
      if (existingRows[0]) {
        const existing = existingRows[0] as {
          acceptance_id: string;
          wallet_address: string;
          status: ClaimRecord["status"];
        };
        acceptanceId = existing.acceptance_id;
        responseCode = 200;

        if (isActiveClaimStatus(existing.status)) {
          if (existing.wallet_address !== input.walletAddress) {
            return { kind: "not_claimable" } as const;
          }

          const claimRows = await tx.unsafe(
            `${SELECT_CLAIM} where ra.acceptance_id = $1::uuid limit 1`,
            [acceptanceId],
          );
          await tx`
            insert into app.idempotency_records(
              idempotency_key,
              actor_id,
              operation_type,
              request_hash,
              status,
              response_code,
              response_body,
              operation_id,
              expires_at
            ) values (
              ${storedKey},
              ${input.actorId}::uuid,
              'CLAIM_PREPARE_V1',
              ${input.requestHash},
              'COMPLETED',
              200,
              ${JSON.stringify({ acceptance_id: acceptanceId })}::jsonb,
              ${acceptanceId}::uuid,
              ${input.idempotencyExpiresAt}
            )
          `;
          return {
            kind: "replayed",
            ...withRefresh(claimRows[0] as unknown as ClaimRow),
          } as const;
        }

        if (existing.status !== "EMPTY") {
          return { kind: "not_claimable" } as const;
        }
      }

      const capacityRows = await tx`
        select count(*)::integer as active_claims
        from app.refresh_acceptances
        where refresh_id = ${input.refreshId}::uuid
          and status in (
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
      `;
      const activeClaims = Number(capacityRows[0].active_claims);
      if (activeClaims >= refresh.maxWitnesses) {
        return { kind: "capacity_full" } as const;
      }

      let claimRevision: number;
      if (existingRows[0]) {
        const updated = await tx`
          update app.refresh_acceptances
          set
            wallet_address = ${input.walletAddress},
            claim_slot = null,
            claim_duration_seconds = ${input.claimDurationSeconds},
            claim_deadline = null,
            chain_signature = null,
            chain_status = 'INTENT_READY',
            status = 'WALLET_PENDING',
            accepted_at = ${input.observedAt},
            released_at = null,
            revision = revision + 1
          where acceptance_id = ${acceptanceId}::uuid
          returning revision
        `;
        claimRevision = Number(updated[0].revision);
      } else {
        const inserted = await tx`
          insert into app.refresh_acceptances(
            acceptance_id,
            refresh_id,
            actor_id,
            wallet_address,
            claim_slot,
            claim_duration_seconds,
            claim_deadline,
            chain_signature,
            chain_status,
            status,
            accepted_at
          ) values (
            ${acceptanceId}::uuid,
            ${input.refreshId}::uuid,
            ${input.actorId}::uuid,
            ${input.walletAddress},
            null,
            ${input.claimDurationSeconds},
            null,
            null,
            'INTENT_READY',
            'WALLET_PENDING',
            ${input.observedAt}
          )
          returning revision
        `;
        claimRevision = Number(inserted[0].revision);
      }

      await tx`
        insert into app.idempotency_records(
          idempotency_key,
          actor_id,
          operation_type,
          request_hash,
          status,
          response_code,
          response_body,
          operation_id,
          expires_at
        ) values (
          ${storedKey},
          ${input.actorId}::uuid,
          'CLAIM_PREPARE_V1',
          ${input.requestHash},
          'COMPLETED',
          ${responseCode},
          ${JSON.stringify({ acceptance_id: acceptanceId })}::jsonb,
          ${acceptanceId}::uuid,
          ${input.idempotencyExpiresAt}
        )
      `;

      await tx`
        insert into app.domain_events(
          event_id,
          entity_type,
          entity_id,
          event_type,
          actor_id,
          operation_id,
          entity_revision,
          payload,
          occurred_at
        ) values (
          ${crypto.randomUUID()}::uuid,
          'claim',
          ${acceptanceId}::uuid,
          'CLAIM_WALLET_PENDING',
          ${input.actorId}::uuid,
          ${acceptanceId}::uuid,
          ${claimRevision},
          ${
        JSON.stringify({
          refresh_id: input.refreshId,
          status: "WALLET_PENDING",
          claim_duration_seconds: input.claimDurationSeconds,
        })
      }::jsonb,
          ${input.observedAt}
        )
      `;

      const claimRows = await tx.unsafe(
        `${SELECT_CLAIM} where ra.acceptance_id = $1::uuid limit 1`,
        [acceptanceId],
      );
      return {
        kind: "prepared",
        created: !existingRows[0],
        ...withRefresh(claimRows[0] as unknown as ClaimRow),
      } as const;
    });
  }

  async getClaim(acceptanceId: string): Promise<ClaimWithRefresh | null> {
    const rows = await this.sql.unsafe(
      `${SELECT_CLAIM} where ra.acceptance_id = $1::uuid limit 1`,
      [acceptanceId],
    );
    return rows[0] ? withRefresh(rows[0] as unknown as ClaimRow) : null;
  }

  async markClaimPending(
    input: Parameters<ClaimRepository["markClaimPending"]>[0],
  ): Promise<ClaimObservationResult> {
    return await this.sql.begin(async (tx) => {
      const rows = await tx.unsafe(
        `${SELECT_CLAIM} where ra.acceptance_id = $1::uuid for update of ra`,
        [input.acceptanceId],
      );
      if (!rows[0]) return { kind: "not_found" } as const;
      const current = withRefresh(rows[0] as unknown as ClaimRow);
      if (current.claim.actorId !== input.actorId) {
        return { kind: "actor_mismatch" } as const;
      }
      if (current.claim.status === "CLAIMED") {
        return { kind: "replayed", ...current } as const;
      }
      if (
        current.claim.chainSignature !== null &&
        current.claim.chainSignature !== input.chainSignature
      ) {
        return { kind: "signature_conflict" } as const;
      }
      if (
        !["WALLET_PENDING", "SUBMITTED", "CONFIRMING", "UNKNOWN"].includes(
          current.claim.status,
        )
      ) {
        return { kind: "authority_conflict" } as const;
      }

      const other = await tx`
        select acceptance_id
        from app.refresh_acceptances
        where chain_signature = ${input.chainSignature}
          and acceptance_id <> ${input.acceptanceId}::uuid
        limit 1
      `;
      if (other[0]) return { kind: "signature_conflict" } as const;

      if (
        current.claim.chainSignature === input.chainSignature &&
        current.claim.status === input.chainStatus
      ) {
        return { kind: "replayed", ...current } as const;
      }

      await tx`
        update app.refresh_acceptances
        set
          chain_signature = ${input.chainSignature},
          chain_status = ${input.chainStatus === "UNKNOWN" ? "UNKNOWN" : "PENDING"},
          status = ${input.chainStatus},
          revision = revision + 1
        where acceptance_id = ${input.acceptanceId}::uuid
      `;

      await tx`
        insert into app.domain_events(
          event_id,
          entity_type,
          entity_id,
          event_type,
          actor_id,
          operation_id,
          payload,
          occurred_at
        ) values (
          ${crypto.randomUUID()}::uuid,
          'claim',
          ${input.acceptanceId}::uuid,
          ${input.chainStatus === "UNKNOWN" ? "CLAIM_OUTCOME_UNKNOWN" : "CLAIM_CONFIRMING"},
          ${input.actorId}::uuid,
          ${input.acceptanceId}::uuid,
          ${JSON.stringify({ chain_signature: input.chainSignature })}::jsonb,
          ${input.observedAt}
        )
      `;

      const updatedRows = await tx.unsafe(
        `${SELECT_CLAIM} where ra.acceptance_id = $1::uuid limit 1`,
        [input.acceptanceId],
      );
      return {
        kind: "updated",
        ...withRefresh(updatedRows[0] as unknown as ClaimRow),
      } as const;
    });
  }

  async resetClaimAbsent(
    input: Parameters<ClaimRepository["resetClaimAbsent"]>[0],
  ): Promise<ClaimObservationResult> {
    return await this.sql.begin(async (tx) => {
      const rows = await tx.unsafe(
        `${SELECT_CLAIM} where ra.acceptance_id = $1::uuid for update of ra`,
        [input.acceptanceId],
      );
      if (!rows[0]) return { kind: "not_found" } as const;
      const current = withRefresh(rows[0] as unknown as ClaimRow);
      if (current.claim.actorId !== input.actorId) {
        return { kind: "actor_mismatch" } as const;
      }
      if (current.claim.status === "CLAIMED") {
        return { kind: "authority_conflict" } as const;
      }
      if (
        !["WALLET_PENDING", "SUBMITTED", "CONFIRMING", "UNKNOWN"].includes(
          current.claim.status,
        )
      ) {
        return { kind: "authority_conflict" } as const;
      }
      if (
        current.claim.chainSignature !== null &&
        current.claim.chainSignature !== input.chainSignature
      ) {
        return { kind: "signature_conflict" } as const;
      }

      await tx`
        update app.refresh_acceptances
        set
          claim_slot = null,
          claim_duration_seconds = null,
          claim_deadline = null,
          chain_signature = null,
          chain_status = 'ABSENT',
          status = 'EMPTY',
          revision = revision + 1
        where acceptance_id = ${input.acceptanceId}::uuid
      `;

      await tx`
        insert into app.domain_events(
          event_id,
          entity_type,
          entity_id,
          event_type,
          actor_id,
          operation_id,
          payload,
          occurred_at
        ) values (
          ${crypto.randomUUID()}::uuid,
          'claim',
          ${input.acceptanceId}::uuid,
          'CLAIM_RECONCILED_ABSENT',
          ${input.actorId}::uuid,
          ${input.acceptanceId}::uuid,
          ${JSON.stringify({ chain_signature: input.chainSignature })}::jsonb,
          ${input.observedAt}
        )
      `;

      const updatedRows = await tx.unsafe(
        `${SELECT_CLAIM} where ra.acceptance_id = $1::uuid limit 1`,
        [input.acceptanceId],
      );
      return {
        kind: "updated",
        ...withRefresh(updatedRows[0] as unknown as ClaimRow),
      } as const;
    });
  }

  async confirmClaim(
    input: Parameters<ClaimRepository["confirmClaim"]>[0],
  ): Promise<ClaimObservationResult> {
    return await this.sql.begin(async (tx) => {
      const identityRows = await tx`
        select ra.refresh_id
        from app.refresh_acceptances ra
        join app.refresh_requests rr
          on rr.refresh_id = ra.refresh_id
         and rr.coordinator_version = 1
        where ra.acceptance_id = ${input.acceptanceId}::uuid
        limit 1
      `;
      if (!identityRows[0]) return { kind: "not_found" } as const;
      const refreshId = identityRows[0].refresh_id as string;

      await tx`select pg_advisory_xact_lock(hashtextextended(${refreshId}, 22))`;
      const refreshRows = await tx.unsafe(
        `${SELECT_REFRESH} where refresh_id = $1::uuid for update`,
        [refreshId],
      );
      if (!refreshRows[0]) return { kind: "authority_conflict" } as const;
      const refresh = refreshFromRow(refreshRows[0] as unknown as RefreshRow);

      const claimRows = await tx.unsafe(
        `${SELECT_CLAIM} where ra.acceptance_id = $1::uuid for update of ra`,
        [input.acceptanceId],
      );
      const current = withRefresh(claimRows[0] as unknown as ClaimRow);
      if (current.claim.actorId !== input.actorId) {
        return { kind: "actor_mismatch" } as const;
      }
      if (current.claim.walletAddress !== input.walletAddress) {
        return { kind: "authority_conflict" } as const;
      }

      if (current.claim.status === "CLAIMED") {
        if (
          current.claim.chainSignature === input.chainSignature &&
          current.claim.claimSlot === input.claimSlot &&
          current.claim.claimDeadline?.getTime() === input.claimDeadline.getTime()
        ) {
          return { kind: "replayed", ...current } as const;
        }
        return { kind: "authority_conflict" } as const;
      }

      if (
        !["WALLET_PENDING", "SUBMITTED", "CONFIRMING", "UNKNOWN"].includes(
          current.claim.status,
        ) ||
        (
          refresh.status !== "AVAILABLE" &&
          refresh.status !== "CLAIMED" &&
          refresh.status !== "ADDITIONAL_VERIFICATION"
        )
      ) {
        return { kind: "authority_conflict" } as const;
      }

      if (
        input.claimSlot < 0 ||
        input.claimSlot >= refresh.maxWitnesses ||
        input.claimedAt.getTime() >= input.claimDeadline.getTime() ||
        input.claimDeadline.getTime() > refresh.refreshExpiresAt.getTime() ||
        input.totalFundedAtomic <= 0n ||
        input.lockedRewardAtomic !== input.totalFundedAtomic ||
        input.totalFundedAtomic < refresh.chainTotalFunded ||
        (refresh.chainLockedReward !== null &&
          refresh.chainLockedReward !== input.lockedRewardAtomic) ||
        input.executionHash.length !== 32
      ) {
        return { kind: "authority_conflict" } as const;
      }

      const signatureRows = await tx`
        select acceptance_id
        from app.refresh_acceptances
        where chain_signature = ${input.chainSignature}
          and acceptance_id <> ${input.acceptanceId}::uuid
        limit 1
      `;
      if (signatureRows[0]) return { kind: "signature_conflict" } as const;

      const slotRows = await tx`
        select acceptance_id
        from app.refresh_acceptances
        where refresh_id = ${refreshId}::uuid
          and claim_slot = ${input.claimSlot}
          and acceptance_id <> ${input.acceptanceId}::uuid
          and status in (
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
        limit 1
      `;
      if (slotRows[0]) return { kind: "authority_conflict" } as const;

      const updatedClaim = await tx`
        update app.refresh_acceptances
        set
          claim_slot = ${input.claimSlot},
          claim_deadline = ${input.claimDeadline},
          chain_signature = ${input.chainSignature},
          chain_status = ${input.chainCommitment},
          status = 'CLAIMED',
          revision = revision + 1
        where acceptance_id = ${input.acceptanceId}::uuid
        returning revision
      `;
      const claimRevision = Number(updatedClaim[0].revision);

      const confirmedRows = await tx`
        select count(*)::integer as confirmed_claims
        from app.refresh_acceptances
        where refresh_id = ${refreshId}::uuid
          and claim_slot is not null
          and status in (
            'CLAIMED',
            'CAPTURE_ACTIVE',
            'EVIDENCE_COMMITTED',
            'RELEASE_ELIGIBLE'
          )
      `;
      const confirmedClaims = Number(confirmedRows[0].confirmed_claims);
      const refreshStatus = refresh.status === "ADDITIONAL_VERIFICATION"
        ? "CLAIMED"
        : refresh.status === "AVAILABLE" &&
            confirmedClaims >= refresh.requiredWitnesses
        ? "CLAIMED"
        : refresh.status;

      const updatedRefresh = await tx`
        update app.refresh_requests
        set
          status = ${refreshStatus},
          chain_status = ${input.chainCommitment},
          chain_total_funded = ${input.totalFundedAtomic.toString()}::numeric,
          chain_locked_reward = ${input.lockedRewardAtomic.toString()}::numeric,
          execution_hash = ${input.executionHash},
          chain_observed_at = ${input.observedAt},
          updated_at = ${input.observedAt},
          revision = revision + 1
        where refresh_id = ${refreshId}::uuid
        returning revision
      `;
      const refreshRevision = Number(updatedRefresh[0].revision);

      await tx`
        insert into app.domain_events(
          event_id,
          entity_type,
          entity_id,
          event_type,
          actor_id,
          operation_id,
          entity_revision,
          payload,
          occurred_at
        ) values (
          ${crypto.randomUUID()}::uuid,
          'claim',
          ${input.acceptanceId}::uuid,
          'CLAIM_CONFIRMED',
          ${input.actorId}::uuid,
          ${input.acceptanceId}::uuid,
          ${claimRevision},
          ${
        JSON.stringify({
          refresh_id: refreshId,
          claim_slot: input.claimSlot,
          chain_signature: input.chainSignature,
          claim_deadline: input.claimDeadline.toISOString(),
        })
      }::jsonb,
          ${input.observedAt}
        ), (
          ${crypto.randomUUID()}::uuid,
          'refresh',
          ${refreshId}::uuid,
          'REFRESH_CLAIM_OBSERVED',
          ${input.actorId}::uuid,
          ${input.acceptanceId}::uuid,
          ${refreshRevision},
          ${
        JSON.stringify({
          status: refreshStatus,
          confirmed_claims: confirmedClaims,
          required_witnesses: refresh.requiredWitnesses,
          locked_reward_atomic: input.lockedRewardAtomic.toString(),
          execution_hash: [...input.executionHash]
            .map((byte) => byte.toString(16).padStart(2, "0"))
            .join(""),
        })
      }::jsonb,
          ${input.observedAt}
        )
      `;

      const resultRows = await tx.unsafe(
        `${SELECT_CLAIM} where ra.acceptance_id = $1::uuid limit 1`,
        [input.acceptanceId],
      );
      return {
        kind: "updated",
        ...withRefresh(resultRows[0] as unknown as ClaimRow),
      } as const;
    });
  }
}
