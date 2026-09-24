import postgres from "npm:postgres@3.4.7";
import type {
  ConfirmFundingResult,
  CreateRefreshResult,
  PrepareFundingResult,
  RefreshRecord,
  RefreshRepository,
} from "./refresh-repository.ts";

type DateLike = Date | string;

type RefreshRow = {
  refresh_id: string;
  state_id: string;
  state_version: number | string;
  requester_actor_id: string;
  status: RefreshRecord["status"];
  verification_class: RefreshRecord["verificationClass"];
  required_witnesses: number | string;
  max_witnesses: number | string;
  proof_policy_snapshot: RefreshRecord["proofPolicySnapshot"];
  proof_policy_digest: Uint8Array;
  intent_core_hash: Uint8Array;
  refresh_expires_at: DateLike;
  evidence_deadline: DateLike;
  reward_mint: string;
  creator_wallet_address: string;
  funding_target_atomic: number | string;
  payout_rule: RefreshRecord["payoutRule"];
  chain_refresh_id: Uint8Array;
  state_id_digest: Uint8Array;
  funding_operation_id: string | null;
  chain_refresh_address: string | null;
  chain_status: string | null;
  chain_total_funded: number | string;
  chain_observed_at: DateLike | null;
  created_at: DateLike;
  updated_at: DateLike;
  revision: number | string;
};

function date(value: DateLike): Date {
  return value instanceof Date ? value : new Date(value);
}

function bytesEqual(left: Uint8Array, right: Uint8Array): boolean {
  if (left.length !== right.length) return false;
  for (let index = 0; index < left.length; index += 1) {
    if (left[index] !== right[index]) return false;
  }
  return true;
}

function fromRow(row: RefreshRow): RefreshRecord {
  return {
    refreshId: row.refresh_id,
    stateId: row.state_id,
    stateVersion: Number(row.state_version),
    requesterActorId: row.requester_actor_id,
    status: row.status,
    verificationClass: row.verification_class,
    requiredWitnesses: Number(row.required_witnesses),
    maxWitnesses: Number(row.max_witnesses),
    proofPolicySnapshot: row.proof_policy_snapshot,
    proofPolicyDigest: new Uint8Array(row.proof_policy_digest),
    intentCoreHash: new Uint8Array(row.intent_core_hash),
    refreshExpiresAt: date(row.refresh_expires_at),
    evidenceDeadline: date(row.evidence_deadline),
    rewardMint: row.reward_mint,
    creatorWalletAddress: row.creator_wallet_address,
    fundingTargetAtomic: BigInt(row.funding_target_atomic),
    payoutRule: row.payout_rule,
    chainRefreshId: new Uint8Array(row.chain_refresh_id),
    stateIdDigest: new Uint8Array(row.state_id_digest),
    fundingOperationId: row.funding_operation_id,
    chainRefreshAddress: row.chain_refresh_address,
    chainStatus: row.chain_status,
    chainTotalFunded: BigInt(row.chain_total_funded),
    chainObservedAt: row.chain_observed_at === null ? null : date(row.chain_observed_at),
    createdAt: date(row.created_at),
    updatedAt: date(row.updated_at),
    revision: Number(row.revision),
  };
}

const SELECT_REFRESH = String.raw`
  select
    refresh_id,
    state_id,
    state_version,
    requester_actor_id,
    status,
    verification_class,
    required_witnesses,
    max_witnesses,
    proof_policy_snapshot,
    proof_policy_digest,
    intent_core_hash,
    refresh_expires_at,
    evidence_deadline,
    reward_mint,
    creator_wallet_address,
    funding_target_atomic,
    payout_rule,
    chain_refresh_id,
    state_id_digest,
    funding_operation_id,
    chain_refresh_address,
    chain_status,
    chain_total_funded,
    chain_observed_at,
    created_at,
    updated_at,
    revision
  from app.refresh_requests
`;

type IdempotencyRow = {
  actor_id: string | null;
  operation_type: string;
  request_hash: Uint8Array;
  operation_id: string | null;
};

function idempotencyMatches(
  row: IdempotencyRow,
  actorId: string,
  operationType: string,
  requestHash: Uint8Array,
): boolean {
  return row.actor_id === actorId &&
    row.operation_type === operationType &&
    row.operation_id !== null &&
    bytesEqual(new Uint8Array(row.request_hash), requestHash);
}

export class PostgresRefreshRepository implements RefreshRepository {
  private readonly sql: ReturnType<typeof postgres>;

  constructor(connectionString: string) {
    this.sql = postgres(connectionString, {
      max: 1,
      prepare: false,
      idle_timeout: 20,
      connect_timeout: 10,
    });
  }

  async createOrReplay(
    input: Parameters<RefreshRepository["createOrReplay"]>[0],
  ): Promise<CreateRefreshResult> {
    return await this.sql.begin(async (tx) => {
      const storedKey =
        `${input.refresh.requesterActorId}:refresh:create:v1:${input.idempotencyKey}`;
      const operationType = "REFRESH_CREATE_V1";

      await tx`select pg_advisory_xact_lock(hashtextextended(${storedKey}, 3))`;
      const existing = await tx`
        select actor_id, operation_type, request_hash, operation_id
        from app.idempotency_records
        where idempotency_key = ${storedKey}
        for update
      `;

      if (existing[0]) {
        const row = existing[0] as IdempotencyRow;
        if (
          !idempotencyMatches(
            row,
            input.refresh.requesterActorId,
            operationType,
            input.requestHash,
          )
        ) {
          return { kind: "idempotency_conflict" };
        }
        const replayRows = await tx.unsafe(
          `${SELECT_REFRESH} where refresh_id = $1::uuid limit 1`,
          [row.operation_id],
        );
        if (!replayRows[0]) throw new Error("idempotency refresh is missing");
        return {
          kind: "replayed",
          refresh: fromRow(replayRows[0] as RefreshRow),
        };
      }

      const refresh = input.refresh;
      const inserted = await tx`
        insert into app.refresh_requests(
          refresh_id,
          state_id,
          state_version,
          requester_actor_id,
          status,
          verification_class,
          required_witnesses,
          max_witnesses,
          proof_policy_snapshot,
          proof_policy_digest,
          intent_core_hash,
          refresh_expires_at,
          evidence_deadline,
          reward_mint,
          coordinator_version,
          creator_wallet_address,
          funding_target_atomic,
          payout_rule,
          chain_refresh_id,
          state_id_digest
        ) values (
          ${refresh.refreshId}::uuid,
          ${refresh.stateId}::uuid,
          ${refresh.stateVersion},
          ${refresh.requesterActorId}::uuid,
          'DRAFT',
          ${refresh.verificationClass},
          ${refresh.requiredWitnesses},
          ${refresh.maxWitnesses},
          ${JSON.stringify(refresh.proofPolicySnapshot)}::jsonb,
          ${refresh.proofPolicyDigest},
          ${refresh.intentCoreHash},
          ${refresh.refreshExpiresAt},
          ${refresh.evidenceDeadline},
          ${refresh.rewardMint},
          1,
          ${refresh.creatorWalletAddress},
          ${refresh.fundingTargetAtomic.toString()}::numeric,
          ${refresh.payoutRule},
          ${refresh.chainRefreshId},
          ${refresh.stateIdDigest}
        )
        returning
          refresh_id,
          state_id,
          state_version,
          requester_actor_id,
          status,
          verification_class,
          required_witnesses,
          max_witnesses,
          proof_policy_snapshot,
          proof_policy_digest,
          intent_core_hash,
          refresh_expires_at,
          evidence_deadline,
          reward_mint,
          creator_wallet_address,
          funding_target_atomic,
          payout_rule,
          chain_refresh_id,
          state_id_digest,
          funding_operation_id,
          chain_refresh_address,
          chain_status,
          chain_total_funded,
          chain_observed_at,
          created_at,
          updated_at,
          revision
      `;

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
          ${refresh.requesterActorId}::uuid,
          ${operationType},
          ${input.requestHash},
          'COMPLETED',
          201,
          ${JSON.stringify({ refresh_id: refresh.refreshId })}::jsonb,
          ${refresh.refreshId}::uuid,
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
          payload
        ) values (
          ${crypto.randomUUID()}::uuid,
          'refresh',
          ${refresh.refreshId}::uuid,
          'REFRESH_DRAFT_CREATED',
          ${refresh.requesterActorId}::uuid,
          ${refresh.refreshId}::uuid,
          1,
          ${JSON.stringify({
            state_id: refresh.stateId,
            status: "DRAFT",
            funding_target_atomic: refresh.fundingTargetAtomic.toString(),
          })}::jsonb
        )
      `;

      return {
        kind: "created",
        refresh: fromRow(inserted[0] as RefreshRow),
      };
    });
  }

  async getRefresh(refreshId: string): Promise<RefreshRecord | null> {
    const rows = await this.sql.unsafe(
      `${SELECT_REFRESH} where refresh_id = $1::uuid limit 1`,
      [refreshId],
    );
    return rows[0] ? fromRow(rows[0] as RefreshRow) : null;
  }

  async prepareFunding(
    input: Parameters<RefreshRepository["prepareFunding"]>[0],
  ): Promise<PrepareFundingResult> {
    return await this.sql.begin(async (tx) => {
      const storedKey =
        `${input.actorId}:refresh:funding-intent:v1:${input.idempotencyKey}`;
      const operationType = "REFRESH_FUNDING_INTENT_V1";
      await tx`select pg_advisory_xact_lock(hashtextextended(${storedKey}, 4))`;

      const rows = await tx.unsafe(
        `${SELECT_REFRESH} where refresh_id = $1::uuid for update`,
        [input.refreshId],
      );
      if (!rows[0]) return { kind: "not_found" } as const;
      const current = fromRow(rows[0] as RefreshRow);
      if (current.requesterActorId !== input.actorId) {
        return { kind: "actor_mismatch" } as const;
      }

      const existing = await tx`
        select actor_id, operation_type, request_hash, operation_id
        from app.idempotency_records
        where idempotency_key = ${storedKey}
        for update
      `;
      if (existing[0]) {
        const row = existing[0] as IdempotencyRow;
        if (!idempotencyMatches(row, input.actorId, operationType, input.requestHash)) {
          return { kind: "idempotency_conflict" } as const;
        }
        if (
          current.fundingOperationId === null ||
          row.operation_id !== current.fundingOperationId
        ) {
          return { kind: "idempotency_conflict" } as const;
        }
        return {
          kind: "replayed",
          refresh: current,
          operationId: current.fundingOperationId,
        } as const;
      }

      if (current.refreshExpiresAt.getTime() <= Date.now()) {
        return { kind: "expired" } as const;
      }
      if (current.status !== "DRAFT" && current.status !== "AWAITING_FUNDING") {
        return { kind: "not_fundable" } as const;
      }

      if (current.status === "AWAITING_FUNDING") {
        if (
          current.fundingOperationId === null ||
          current.chainRefreshAddress !== input.addresses.refreshAddress
        ) {
          return { kind: "not_fundable" } as const;
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
            ${operationType},
            ${input.requestHash},
            'COMPLETED',
            200,
            ${JSON.stringify({ refresh_id: current.refreshId })}::jsonb,
            ${current.fundingOperationId}::uuid,
            ${input.idempotencyExpiresAt}
          )
        `;

        return {
          kind: "replayed",
          refresh: current,
          operationId: current.fundingOperationId,
        } as const;
      }

      const updated = await tx`
        update app.refresh_requests
        set
          status = 'AWAITING_FUNDING',
          funding_operation_id = ${input.operationId}::uuid,
          chain_refresh_address = ${input.addresses.refreshAddress},
          chain_status = 'INTENT_READY',
          updated_at = now(),
          revision = revision + 1
        where refresh_id = ${input.refreshId}::uuid
        returning
          refresh_id,
          state_id,
          state_version,
          requester_actor_id,
          status,
          verification_class,
          required_witnesses,
          max_witnesses,
          proof_policy_snapshot,
          proof_policy_digest,
          intent_core_hash,
          refresh_expires_at,
          evidence_deadline,
          reward_mint,
          creator_wallet_address,
          funding_target_atomic,
          payout_rule,
          chain_refresh_id,
          state_id_digest,
          funding_operation_id,
          chain_refresh_address,
          chain_status,
          chain_total_funded,
          chain_observed_at,
          created_at,
          updated_at,
          revision
      `;

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
          ${operationType},
          ${input.requestHash},
          'COMPLETED',
          200,
          ${JSON.stringify({ refresh_id: current.refreshId })}::jsonb,
          ${input.operationId}::uuid,
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
          payload
        ) values (
          ${crypto.randomUUID()}::uuid,
          'refresh',
          ${input.refreshId}::uuid,
          'REFRESH_FUNDING_INTENT_CREATED',
          ${input.actorId}::uuid,
          ${input.operationId}::uuid,
          ${current.revision + 1},
          ${JSON.stringify({
            status: "AWAITING_FUNDING",
            refresh_address: input.addresses.refreshAddress,
          })}::jsonb
        )
      `;

      return {
        kind: "prepared",
        refresh: fromRow(updated[0] as RefreshRow),
        operationId: input.operationId,
      } as const;
    });
  }

  async confirmFunding(
    input: Parameters<RefreshRepository["confirmFunding"]>[0],
  ): Promise<ConfirmFundingResult> {
    return await this.sql.begin(async (tx) => {
      const storedKey =
        `${input.actorId}:refresh:funding-observe:v1:${input.idempotencyKey}`;
      const operationType = "REFRESH_FUNDING_OBSERVE_V1";
      await tx`select pg_advisory_xact_lock(hashtextextended(${storedKey}, 5))`;

      const rows = await tx.unsafe(
        `${SELECT_REFRESH} where refresh_id = $1::uuid for update`,
        [input.refreshId],
      );
      if (!rows[0]) return { kind: "not_found" } as const;
      const current = fromRow(rows[0] as RefreshRow);
      if (current.requesterActorId !== input.actorId) {
        return { kind: "actor_mismatch" } as const;
      }

      const existing = await tx`
        select actor_id, operation_type, request_hash, operation_id
        from app.idempotency_records
        where idempotency_key = ${storedKey}
        for update
      `;
      if (existing[0]) {
        const row = existing[0] as IdempotencyRow;
        if (!idempotencyMatches(row, input.actorId, operationType, input.requestHash)) {
          return { kind: "idempotency_conflict" } as const;
        }
        if (
          current.fundingOperationId === null ||
          row.operation_id !== current.fundingOperationId
        ) {
          return { kind: "idempotency_conflict" } as const;
        }
        return { kind: "replayed", refresh: current } as const;
      }

      if (current.refreshExpiresAt.getTime() <= input.observedAt.getTime()) {
        return { kind: "expired" } as const;
      }
      if (
        current.status !== "AWAITING_FUNDING" ||
        current.fundingOperationId === null ||
        current.chainRefreshAddress !== input.chainRefreshAddress ||
        input.chainTotalFundedAtomic < current.fundingTargetAtomic
      ) {
        return { kind: "not_fundable" } as const;
      }

      const signatureRows = await tx`
        select refresh_id, operation_id
        from app.refresh_contributions
        where chain_signature = ${input.chainSignature}
        limit 1
      `;
      if (signatureRows[0]) {
        if (
          signatureRows[0].refresh_id !== input.refreshId ||
          signatureRows[0].operation_id !== current.fundingOperationId
        ) {
          return { kind: "not_fundable" } as const;
        }
        return { kind: "replayed", refresh: current } as const;
      }

      await tx`
        insert into app.refresh_contributions(
          contribution_id,
          refresh_id,
          actor_id,
          wallet_address,
          operation_id,
          amount_atomic,
          chain_contribution_address,
          chain_signature,
          chain_commitment,
          status,
          created_at,
          updated_at
        ) values (
          ${crypto.randomUUID()}::uuid,
          ${input.refreshId}::uuid,
          ${input.actorId}::uuid,
          ${current.creatorWalletAddress},
          ${current.fundingOperationId}::uuid,
          ${input.contributionAmountAtomic.toString()}::numeric,
          ${input.chainContributionAddress},
          ${input.chainSignature},
          ${input.chainCommitment},
          ${input.chainCommitment === "finalized" ? "FINALIZED" : "CONFIRMED"},
          ${input.observedAt},
          ${input.observedAt}
        )
      `;

      const updated = await tx`
        update app.refresh_requests
        set
          status = 'AVAILABLE',
          chain_status = ${input.chainCommitment},
          chain_total_funded = ${input.chainTotalFundedAtomic.toString()}::numeric,
          chain_observed_at = ${input.observedAt},
          updated_at = ${input.observedAt},
          revision = revision + 2
        where refresh_id = ${input.refreshId}::uuid
        returning
          refresh_id,
          state_id,
          state_version,
          requester_actor_id,
          status,
          verification_class,
          required_witnesses,
          max_witnesses,
          proof_policy_snapshot,
          proof_policy_digest,
          intent_core_hash,
          refresh_expires_at,
          evidence_deadline,
          reward_mint,
          creator_wallet_address,
          funding_target_atomic,
          payout_rule,
          chain_refresh_id,
          state_id_digest,
          funding_operation_id,
          chain_refresh_address,
          chain_status,
          chain_total_funded,
          chain_observed_at,
          created_at,
          updated_at,
          revision
      `;

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
          ${operationType},
          ${input.requestHash},
          'COMPLETED',
          200,
          ${JSON.stringify({ refresh_id: input.refreshId })}::jsonb,
          ${current.fundingOperationId}::uuid,
          ${input.idempotencyExpiresAt}
        )
      `;

      const fundedRevision = current.revision + 1;
      const availableRevision = current.revision + 2;
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
          'refresh',
          ${input.refreshId}::uuid,
          'REFRESH_FUNDING_CONFIRMED',
          ${input.actorId}::uuid,
          ${current.fundingOperationId}::uuid,
          ${fundedRevision},
          ${JSON.stringify({
            status: "FUNDED",
            chain_signature: input.chainSignature,
            chain_total_funded_atomic: input.chainTotalFundedAtomic.toString(),
          })}::jsonb,
          ${input.observedAt}
        ), (
          ${crypto.randomUUID()}::uuid,
          'refresh',
          ${input.refreshId}::uuid,
          'REFRESH_AVAILABLE',
          ${input.actorId}::uuid,
          ${current.fundingOperationId}::uuid,
          ${availableRevision},
          ${JSON.stringify({ status: "AVAILABLE" })}::jsonb,
          ${input.observedAt}
        )
      `;

      return {
        kind: "confirmed",
        refresh: fromRow(updated[0] as RefreshRow),
      } as const;
    });
  }
}
