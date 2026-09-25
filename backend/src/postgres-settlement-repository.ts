import postgres from "npm:postgres@3.4.7";
import type {
  CreateSettlementResult,
  PrepareSettlementResult,
  SettlementBeneficiary,
  SettlementEligibility,
  SettlementMutationResult,
  SettlementOperation,
  SettlementRepository,
} from "./settlement-repository.ts";
import type {
  RefreshStatus,
  SettlementStatus,
} from "../../packages/contracts/src/lifecycle.ts";

type DateLike = Date | string;

type EligibilityRow = {
  refresh_id: string;
  refresh_status: RefreshStatus;
  required_witnesses: number | string;
  max_witnesses: number | string;
  chain_refresh_id: Uint8Array | null;
  chain_refresh_address: string | null;
  refresh_expires_at: DateLike;
  reward_mint: string;
  chain_locked_reward: number | string | null;
  refresh_execution_hash: Uint8Array | null;
  verification_result_id: string;
  verification_digest: Uint8Array;
  verification_execution_hash: Uint8Array;
  matching_evidence_ids: string[];
};

type BeneficiaryRow = {
  evidence_id: string;
  acceptance_id: string;
  actor_id: string;
  wallet_address: string;
  claim_slot: number | string | null;
};

type OperationRow = {
  settlement_id: string;
  refresh_id: string;
  verification_result_id: string;
  operation_id: string;
  operation_hash: Uint8Array;
  verification_digest: Uint8Array;
  execution_hash: Uint8Array;
  recipient_mask: number | string;
  recipient_wallets: string[];
  chain_refresh_id: Uint8Array;
  reward_mint: string;
  locked_reward_atomic: number | string;
  chain_refresh_address: string;
  refresh_expires_at: DateLike;
  status: SettlementStatus;
  chain_signature: string | null;
  recent_blockhash: string | null;
  last_valid_block_height: number | string | null;
  chain_commitment: string | null;
  attempt_count: number | string;
  next_reconcile_at: DateLike | null;
  last_chain_observed_at: DateLike | null;
  last_error_code: string | null;
  created_at: DateLike;
  updated_at: DateLike;
  confirmed_at: DateLike | null;
  finalized_at: DateLike | null;
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

function stringsEqual(left: readonly string[], right: readonly string[]): boolean {
  return left.length === right.length &&
    left.every((value, index) => value === right[index]);
}

function beneficiariesEqual(
  left: readonly SettlementBeneficiary[],
  right: readonly SettlementBeneficiary[],
): boolean {
  return left.length === right.length &&
    left.every((value, index) => {
      const other = right[index];
      return other !== undefined &&
        value.evidenceId === other.evidenceId &&
        value.acceptanceId === other.acceptanceId &&
        value.actorId === other.actorId &&
        value.walletAddress === other.walletAddress &&
        value.claimSlot === other.claimSlot;
    });
}

function operationFromRow(row: OperationRow): SettlementOperation {
  return {
    settlementId: row.settlement_id,
    refreshId: row.refresh_id,
    verificationResultId: row.verification_result_id,
    operationId: row.operation_id,
    operationHash: new Uint8Array(row.operation_hash),
    verificationDigest: new Uint8Array(row.verification_digest),
    executionHash: new Uint8Array(row.execution_hash),
    recipientMask: Number(row.recipient_mask),
    recipientWallets: row.recipient_wallets,
    chainRefreshId: new Uint8Array(row.chain_refresh_id),
    rewardMint: row.reward_mint,
    lockedRewardAtomic: BigInt(row.locked_reward_atomic),
    chainRefreshAddress: row.chain_refresh_address,
    refreshExpiresAt: date(row.refresh_expires_at),
    status: row.status,
    chainSignature: row.chain_signature,
    recentBlockhash: row.recent_blockhash,
    lastValidBlockHeight: row.last_valid_block_height === null
      ? null
      : Number(row.last_valid_block_height),
    chainCommitment: row.chain_commitment,
    attemptCount: Number(row.attempt_count),
    nextReconcileAt: row.next_reconcile_at === null ? null : date(row.next_reconcile_at),
    lastChainObservedAt: row.last_chain_observed_at === null
      ? null
      : date(row.last_chain_observed_at),
    lastErrorCode: row.last_error_code,
    createdAt: date(row.created_at),
    updatedAt: date(row.updated_at),
    confirmedAt: row.confirmed_at === null ? null : date(row.confirmed_at),
    finalizedAt: row.finalized_at === null ? null : date(row.finalized_at),
  };
}

const SELECT_OPERATION = String.raw`
  select
    settlement_id,
    refresh_id,
    verification_result_id,
    operation_id,
    operation_hash,
    verification_digest,
    execution_hash,
    recipient_mask,
    recipient_wallets,
    chain_refresh_id,
    reward_mint,
    locked_reward_atomic,
    chain_refresh_address,
    refresh_expires_at,
    status,
    chain_signature,
    recent_blockhash,
    last_valid_block_height,
    chain_commitment,
    attempt_count,
    next_reconcile_at,
    last_chain_observed_at,
    last_error_code,
    created_at,
    updated_at,
    confirmed_at,
    finalized_at
  from app.settlement_operations
`;

const SELECT_ELIGIBILITY = String.raw`
  select
    rr.refresh_id,
    rr.status as refresh_status,
    rr.required_witnesses,
    rr.max_witnesses,
    rr.chain_refresh_id,
    rr.chain_refresh_address,
    rr.refresh_expires_at,
    rr.reward_mint,
    rr.chain_locked_reward,
    rr.execution_hash as refresh_execution_hash,
    vr.verification_result_id,
    vr.canonical_digest as verification_digest,
    vr.execution_hash as verification_execution_hash,
    coalesce(
      array(
        select jsonb_array_elements_text(
          coalesce(vr.verification_trace -> 'matching_evidence_ids', '[]'::jsonb)
        )
      ),
      array[]::text[]
    ) as matching_evidence_ids
  from app.refresh_requests rr
  join app.verification_results vr
    on vr.refresh_id = rr.refresh_id
   and vr.status = 'VERIFIED'
  join app.live_states ls
    on ls.state_id = rr.state_id
   and ls.latest_refresh_id = rr.refresh_id
   and ls.latest_verification_result_id = vr.verification_result_id
  left join app.settlement_operations so
    on so.refresh_id = rr.refresh_id
  where rr.status = 'VERIFIED'
    and rr.refresh_expires_at > now()
    and so.settlement_id is null
  order by vr.completed_at asc, rr.refresh_id asc
  limit 1
`;

const SELECT_LOCKED_ELIGIBILITY = String.raw`
  select
    rr.refresh_id,
    rr.status as refresh_status,
    rr.required_witnesses,
    rr.max_witnesses,
    rr.chain_refresh_id,
    rr.chain_refresh_address,
    rr.refresh_expires_at,
    rr.reward_mint,
    rr.chain_locked_reward,
    rr.execution_hash as refresh_execution_hash,
    vr.verification_result_id,
    vr.canonical_digest as verification_digest,
    vr.execution_hash as verification_execution_hash,
    coalesce(
      array(
        select jsonb_array_elements_text(
          coalesce(vr.verification_trace -> 'matching_evidence_ids', '[]'::jsonb)
        )
      ),
      array[]::text[]
    ) as matching_evidence_ids
  from app.refresh_requests rr
  join app.verification_results vr
    on vr.refresh_id = rr.refresh_id
   and vr.status = 'VERIFIED'
  join app.live_states ls
    on ls.state_id = rr.state_id
   and ls.latest_refresh_id = rr.refresh_id
   and ls.latest_verification_result_id = vr.verification_result_id
  left join app.settlement_operations so
    on so.refresh_id = rr.refresh_id
  where rr.refresh_id = $1::uuid
    and vr.verification_result_id = $2::uuid
    and rr.status = 'VERIFIED'
    and rr.refresh_expires_at > now()
    and so.settlement_id is null
  limit 1
`;

const SELECT_BENEFICIARIES = String.raw`
  select
    ep.evidence_id,
    ep.acceptance_id,
    ep.actor_id,
    ep.wallet_address,
    ra.claim_slot
  from app.evidence_packets ep
  join app.refresh_acceptances ra
    on ra.acceptance_id = ep.acceptance_id
   and ra.refresh_id = ep.refresh_id
   and ra.actor_id = ep.actor_id
  where ep.refresh_id = $1::uuid
    and ep.evidence_id = any($2::uuid[])
    and ep.status = 'VERIFIED'
  order by ra.claim_slot asc, ep.evidence_id asc
`;

function beneficiaryFromRow(row: BeneficiaryRow): SettlementBeneficiary {
  if (row.claim_slot === null) {
    throw new Error("verified settlement beneficiary has no claim slot");
  }
  return {
    evidenceId: row.evidence_id,
    acceptanceId: row.acceptance_id,
    actorId: row.actor_id,
    walletAddress: row.wallet_address,
    claimSlot: Number(row.claim_slot),
  };
}

function validateEligibility(
  row: EligibilityRow,
  beneficiaries: SettlementBeneficiary[],
): SettlementEligibility {
  const requiredWitnesses = Number(row.required_witnesses);
  const maxWitnesses = Number(row.max_witnesses);
  if (
    row.refresh_status !== "VERIFIED" ||
    row.chain_refresh_id === null ||
    row.chain_refresh_id.length !== 32 ||
    row.chain_refresh_address === null ||
    row.chain_refresh_address.trim().length === 0 ||
    row.chain_locked_reward === null ||
    BigInt(row.chain_locked_reward) <= 0n ||
    row.refresh_execution_hash === null ||
    row.refresh_execution_hash.length !== 32 ||
    row.verification_digest.length !== 32 ||
    row.verification_execution_hash.length !== 32 ||
    !bytesEqual(row.refresh_execution_hash, row.verification_execution_hash) ||
    beneficiaries.length !== requiredWitnesses ||
    requiredWitnesses < 1 ||
    maxWitnesses < requiredWitnesses ||
    maxWitnesses > 3
  ) {
    throw new Error("verified refresh settlement authority is inconsistent");
  }

  const seenSlots = new Set<number>();
  for (const beneficiary of beneficiaries) {
    if (
      beneficiary.claimSlot < 0 ||
      beneficiary.claimSlot >= maxWitnesses ||
      seenSlots.has(beneficiary.claimSlot) ||
      beneficiary.walletAddress.trim().length === 0
    ) {
      throw new Error("verified settlement beneficiary set is inconsistent");
    }
    seenSlots.add(beneficiary.claimSlot);
  }

  return {
    refreshId: row.refresh_id,
    refreshStatus: row.refresh_status,
    verificationResultId: row.verification_result_id,
    verificationDigest: new Uint8Array(row.verification_digest),
    executionHash: new Uint8Array(row.refresh_execution_hash),
    chainRefreshId: new Uint8Array(row.chain_refresh_id),
    chainRefreshAddress: row.chain_refresh_address,
    refreshExpiresAt: date(row.refresh_expires_at),
    rewardMint: row.reward_mint,
    lockedRewardAtomic: BigInt(row.chain_locked_reward),
    requiredWitnesses,
    maxWitnesses,
    beneficiaries,
  };
}

export class PostgresSettlementRepository implements SettlementRepository {
  private readonly sql: ReturnType<typeof postgres>;

  constructor(connectionString: string) {
    this.sql = postgres(connectionString, {
      max: 1,
      prepare: false,
      idle_timeout: 20,
      connect_timeout: 10,
    });
  }

  async findEligible(): Promise<PrepareSettlementResult> {
    const rows = await this.sql.unsafe(SELECT_ELIGIBILITY);
    if (!rows[0]) return { kind: "none" };
    const row = rows[0] as unknown as EligibilityRow;
    const beneficiaryRows = await this.sql.unsafe(SELECT_BENEFICIARIES, [
      row.refresh_id,
      row.matching_evidence_ids,
    ]);
    const beneficiaries = beneficiaryRows.map((item) =>
      beneficiaryFromRow(item as unknown as BeneficiaryRow)
    );
    return {
      kind: "eligible",
      eligibility: validateEligibility(row, beneficiaries),
    };
  }

  async createOperation(
    input: Parameters<SettlementRepository["createOperation"]>[0],
  ): Promise<CreateSettlementResult> {
    return await this.sql.begin(async (tx) => {
      await tx`
        select pg_advisory_xact_lock(
          hashtextextended(${input.eligibility.refreshId}, 71)
        )
      `;

      const existingRows = await tx.unsafe(
        `${SELECT_OPERATION} where refresh_id = $1::uuid limit 1 for update`,
        [input.eligibility.refreshId],
      );
      if (existingRows[0]) {
        const existing = operationFromRow(existingRows[0] as unknown as OperationRow);
        const wallets = input.eligibility.beneficiaries.map((item) => item.walletAddress);
        if (
          existing.verificationResultId !== input.eligibility.verificationResultId ||
          !bytesEqual(existing.operationHash, input.operationHash) ||
          !bytesEqual(existing.verificationDigest, input.eligibility.verificationDigest) ||
          !bytesEqual(existing.executionHash, input.eligibility.executionHash) ||
          existing.recipientMask !== input.recipientMask ||
          !stringsEqual(existing.recipientWallets, wallets) ||
          !bytesEqual(existing.chainRefreshId, input.eligibility.chainRefreshId)
        ) {
          return { kind: "authority_conflict" } as const;
        }
        return { kind: "replayed", operation: existing } as const;
      }

      const authorityRows = await tx.unsafe(
        `${SELECT_LOCKED_ELIGIBILITY} for update of rr, vr`,
        [
          input.eligibility.refreshId,
          input.eligibility.verificationResultId,
        ],
      );
      if (!authorityRows[0]) return { kind: "not_eligible" } as const;
      const authority = authorityRows[0] as unknown as EligibilityRow;
      const beneficiaryRows = await tx.unsafe(SELECT_BENEFICIARIES, [
        input.eligibility.refreshId,
        authority.matching_evidence_ids,
      ]);
      const lockedBeneficiaries = beneficiaryRows.map((item) =>
        beneficiaryFromRow(item as unknown as BeneficiaryRow)
      );
      const locked = validateEligibility(authority, lockedBeneficiaries);
      if (
        !bytesEqual(locked.verificationDigest, input.eligibility.verificationDigest) ||
        !bytesEqual(locked.executionHash, input.eligibility.executionHash) ||
        !bytesEqual(locked.chainRefreshId, input.eligibility.chainRefreshId) ||
        locked.chainRefreshAddress !== input.eligibility.chainRefreshAddress ||
        locked.refreshExpiresAt.getTime() !==
          input.eligibility.refreshExpiresAt.getTime() ||
        locked.rewardMint !== input.eligibility.rewardMint ||
        locked.lockedRewardAtomic !== input.eligibility.lockedRewardAtomic ||
        !beneficiariesEqual(locked.beneficiaries, input.eligibility.beneficiaries)
      ) {
        return { kind: "authority_conflict" } as const;
      }

      const wallets = locked.beneficiaries.map((item) => item.walletAddress);
      await tx`
        insert into app.settlement_operations(
          settlement_id,
          refresh_id,
          verification_result_id,
          operation_id,
          operation_hash,
          verification_digest,
          execution_hash,
          recipient_mask,
          recipient_wallets,
          chain_refresh_id,
          reward_mint,
          locked_reward_atomic,
          chain_refresh_address,
          refresh_expires_at,
          status,
          attempt_count,
          next_reconcile_at,
          created_at,
          updated_at
        ) values (
          ${input.settlementId}::uuid,
          ${locked.refreshId}::uuid,
          ${locked.verificationResultId}::uuid,
          ${input.operationId}::uuid,
          ${input.operationHash},
          ${locked.verificationDigest},
          ${locked.executionHash},
          ${input.recipientMask},
          ${wallets},
          ${locked.chainRefreshId},
          ${locked.rewardMint},
          ${locked.lockedRewardAtomic.toString()}::numeric,
          ${locked.chainRefreshAddress},
          ${locked.refreshExpiresAt},
          'ELIGIBLE',
          0,
          ${input.observedAt},
          ${input.observedAt},
          ${input.observedAt}
        )
      `;

      const refreshed = await tx`
        update app.refresh_requests
        set
          status = 'SETTLEMENT_PENDING',
          updated_at = ${input.observedAt},
          revision = revision + 1
        where refresh_id = ${locked.refreshId}::uuid
          and status = 'VERIFIED'
        returning revision
      `;
      if (!refreshed[0]) {
        throw new Error("settlement operation lost verified refresh authority");
      }
      const refreshRevision = Number(refreshed[0].revision);

      await tx`
        insert into app.domain_events(
          event_id,
          entity_type,
          entity_id,
          event_type,
          operation_id,
          entity_revision,
          payload,
          occurred_at
        ) values (
          ${crypto.randomUUID()}::uuid,
          'settlement',
          ${input.settlementId}::uuid,
          'SETTLEMENT_ELIGIBLE',
          ${input.operationId}::uuid,
          1,
          ${JSON.stringify({
            refresh_id: locked.refreshId,
            verification_result_id: locked.verificationResultId,
            recipient_mask: input.recipientMask,
            recipient_wallets: wallets,
            locked_reward_atomic: locked.lockedRewardAtomic.toString(),
          })}::jsonb,
          ${input.observedAt}
        ), (
          ${crypto.randomUUID()}::uuid,
          'refresh',
          ${locked.refreshId}::uuid,
          'REFRESH_SETTLEMENT_STARTED',
          ${input.operationId}::uuid,
          ${refreshRevision},
          ${JSON.stringify({ settlement_id: input.settlementId })}::jsonb,
          ${input.observedAt}
        )
      `;

      const createdRows = await tx.unsafe(
        `${SELECT_OPERATION} where settlement_id = $1::uuid limit 1`,
        [input.settlementId],
      );
      return {
        kind: "created",
        operation: operationFromRow(createdRows[0] as unknown as OperationRow),
      } as const;
    });
  }

  async listDue(
    input: Parameters<SettlementRepository["listDue"]>[0],
  ): Promise<SettlementOperation[]> {
    const limit = Math.max(1, Math.min(100, Math.trunc(input.limit)));
    const rows = await this.sql.unsafe(
      `${SELECT_OPERATION}
       where status in ('ELIGIBLE', 'SUBMITTED', 'VERIFYING', 'CONFIRMED', 'NOT_SETTLED')
         and (next_reconcile_at is null or next_reconcile_at <= $1)
       order by coalesce(next_reconcile_at, created_at), created_at, settlement_id
       limit $2`,
      [input.observedAt, limit],
    );
    return rows.map((row) => operationFromRow(row as unknown as OperationRow));
  }

  async markAttempt(
    input: Parameters<SettlementRepository["markAttempt"]>[0],
  ): Promise<SettlementMutationResult> {
    return await this.sql.begin(async (tx) => {
      const rows = await tx.unsafe(
        `${SELECT_OPERATION} where settlement_id = $1::uuid limit 1 for update`,
        [input.settlementId],
      );
      if (!rows[0]) return { kind: "not_found" } as const;
      const current = operationFromRow(rows[0] as unknown as OperationRow);

      if (
        current.chainSignature === input.chainSignature &&
        current.recentBlockhash === input.recentBlockhash &&
        current.lastValidBlockHeight === input.lastValidBlockHeight &&
        ["SUBMITTED", "VERIFYING", "CONFIRMED", "FINALIZED"].includes(current.status)
      ) {
        return { kind: "replayed", operation: current } as const;
      }
      if (!["ELIGIBLE", "NOT_SETTLED"].includes(current.status)) {
        return { kind: "authority_conflict" } as const;
      }

      const signatureRows = await tx`
        select settlement_id
        from app.settlement_operations
        where chain_signature = ${input.chainSignature}
          and settlement_id <> ${input.settlementId}::uuid
        limit 1
      `;
      if (signatureRows[0]) return { kind: "authority_conflict" } as const;

      const status: SettlementStatus = input.ambiguous ? "VERIFYING" : "SUBMITTED";
      await tx`
        update app.settlement_operations
        set
          status = ${status},
          chain_signature = ${input.chainSignature},
          recent_blockhash = ${input.recentBlockhash},
          last_valid_block_height = ${input.lastValidBlockHeight},
          chain_commitment = null,
          attempt_count = attempt_count + 1,
          next_reconcile_at = ${input.nextReconcileAt},
          last_chain_observed_at = ${input.observedAt},
          last_error_code = ${input.errorCode},
          updated_at = ${input.observedAt}
        where settlement_id = ${input.settlementId}::uuid
      `;

      if (input.ambiguous) {
        await tx`
          update app.refresh_requests
          set
            status = case
              when status = 'SETTLEMENT_PENDING' then 'SETTLEMENT_VERIFYING'::app.refresh_status
              else status
            end,
            updated_at = ${input.observedAt},
            revision = revision + 1
          where refresh_id = ${current.refreshId}::uuid
            and status in ('SETTLEMENT_PENDING', 'SETTLEMENT_VERIFYING')
        `;
      }

      await tx`
        insert into app.domain_events(
          event_id,
          entity_type,
          entity_id,
          event_type,
          operation_id,
          payload,
          occurred_at
        ) values (
          ${crypto.randomUUID()}::uuid,
          'settlement',
          ${current.settlementId}::uuid,
          ${input.ambiguous ? "SETTLEMENT_OUTCOME_UNKNOWN" : "SETTLEMENT_SUBMITTED"},
          ${current.operationId}::uuid,
          ${JSON.stringify({
            chain_signature: input.chainSignature,
            last_valid_block_height: input.lastValidBlockHeight,
            attempt: current.attemptCount + 1,
          })}::jsonb,
          ${input.observedAt}
        )
      `;

      const updatedRows = await tx.unsafe(
        `${SELECT_OPERATION} where settlement_id = $1::uuid limit 1`,
        [input.settlementId],
      );
      return {
        kind: "updated",
        operation: operationFromRow(updatedRows[0] as unknown as OperationRow),
      } as const;
    });
  }

  async markConfirmed(
    input: Parameters<SettlementRepository["markConfirmed"]>[0],
  ): Promise<SettlementMutationResult> {
    return await this.sql.begin(async (tx) => {
      const rows = await tx.unsafe(
        `${SELECT_OPERATION} where settlement_id = $1::uuid limit 1 for update`,
        [input.settlementId],
      );
      if (!rows[0]) return { kind: "not_found" } as const;
      const current = operationFromRow(rows[0] as unknown as OperationRow);
      if (
        current.chainSignature !== null &&
        current.chainSignature !== input.chainSignature
      ) {
        return { kind: "authority_conflict" } as const;
      }
      if (current.status === "FINALIZED") {
        return { kind: "replayed", operation: current } as const;
      }
      if (
        !["SUBMITTED", "VERIFYING", "CONFIRMED"].includes(current.status)
      ) {
        return { kind: "authority_conflict" } as const;
      }

      const finalized = input.commitment === "finalized";
      await tx`
        update app.settlement_operations
        set
          status = ${finalized ? "FINALIZED" : "CONFIRMED"},
          chain_signature = ${input.chainSignature},
          chain_commitment = ${input.commitment},
          last_chain_observed_at = ${input.observedAt},
          confirmed_at = coalesce(confirmed_at, ${input.observedAt}),
          finalized_at = case
            when ${finalized} then coalesce(finalized_at, ${input.observedAt})
            else finalized_at
          end,
          next_reconcile_at = case
            when ${finalized} then null
            else ${new Date(input.observedAt.getTime() + 30_000)}
          end,
          last_error_code = null,
          updated_at = ${input.observedAt}
        where settlement_id = ${input.settlementId}::uuid
      `;

      await tx`
        update app.refresh_requests
        set
          status = 'COMPLETED',
          chain_status = ${"SETTLED_" + input.commitment.toUpperCase()},
          chain_observed_at = ${input.observedAt},
          updated_at = ${input.observedAt},
          revision = revision + 1
        where refresh_id = ${current.refreshId}::uuid
          and status in ('SETTLEMENT_PENDING', 'SETTLEMENT_VERIFYING')
      `;

      await tx`
        insert into app.domain_events(
          event_id,
          entity_type,
          entity_id,
          event_type,
          operation_id,
          payload,
          occurred_at
        ) values (
          ${crypto.randomUUID()}::uuid,
          'settlement',
          ${current.settlementId}::uuid,
          ${finalized ? "SETTLEMENT_FINALIZED" : "SETTLEMENT_CONFIRMED"},
          ${current.operationId}::uuid,
          ${JSON.stringify({
            chain_signature: input.chainSignature,
            commitment: input.commitment,
          })}::jsonb,
          ${input.observedAt}
        )
      `;

      const updatedRows = await tx.unsafe(
        `${SELECT_OPERATION} where settlement_id = $1::uuid limit 1`,
        [input.settlementId],
      );
      return {
        kind: "updated",
        operation: operationFromRow(updatedRows[0] as unknown as OperationRow),
      } as const;
    });
  }

  async markNotSettled(
    input: Parameters<SettlementRepository["markNotSettled"]>[0],
  ): Promise<SettlementMutationResult> {
    return await this.sql.begin(async (tx) => {
      const rows = await tx.unsafe(
        `${SELECT_OPERATION} where settlement_id = $1::uuid limit 1 for update`,
        [input.settlementId],
      );
      if (!rows[0]) return { kind: "not_found" } as const;
      const current = operationFromRow(rows[0] as unknown as OperationRow);
      if (current.status === "NOT_SETTLED") {
        return { kind: "replayed", operation: current } as const;
      }
      if (!["SUBMITTED", "VERIFYING"].includes(current.status)) {
        return { kind: "authority_conflict" } as const;
      }

      await tx`
        update app.settlement_operations
        set
          status = 'NOT_SETTLED',
          chain_commitment = null,
          next_reconcile_at = ${input.observedAt},
          last_chain_observed_at = ${input.observedAt},
          last_error_code = 'CHAIN_ATTEMPT_EXPIRED',
          updated_at = ${input.observedAt}
        where settlement_id = ${input.settlementId}::uuid
      `;

      await tx`
        update app.refresh_requests
        set
          status = 'SETTLEMENT_PENDING',
          updated_at = ${input.observedAt},
          revision = revision + 1
        where refresh_id = ${current.refreshId}::uuid
          and status = 'SETTLEMENT_VERIFYING'
      `;

      await tx`
        insert into app.domain_events(
          event_id,
          entity_type,
          entity_id,
          event_type,
          operation_id,
          payload,
          occurred_at
        ) values (
          ${crypto.randomUUID()}::uuid,
          'settlement',
          ${current.settlementId}::uuid,
          'SETTLEMENT_RECONCILED_ABSENT',
          ${current.operationId}::uuid,
          ${JSON.stringify({
            chain_signature: current.chainSignature,
            attempt: current.attemptCount,
          })}::jsonb,
          ${input.observedAt}
        )
      `;

      const updatedRows = await tx.unsafe(
        `${SELECT_OPERATION} where settlement_id = $1::uuid limit 1`,
        [input.settlementId],
      );
      return {
        kind: "updated",
        operation: operationFromRow(updatedRows[0] as unknown as OperationRow),
      } as const;
    });
  }

  async defer(
    input: Parameters<SettlementRepository["defer"]>[0],
  ): Promise<SettlementMutationResult> {
    return await this.sql.begin(async (tx) => {
      const rows = await tx.unsafe(
        `${SELECT_OPERATION} where settlement_id = $1::uuid limit 1 for update`,
        [input.settlementId],
      );
      if (!rows[0]) return { kind: "not_found" } as const;
      const current = operationFromRow(rows[0] as unknown as OperationRow);
      if (["FINALIZED", "FAILED"].includes(current.status)) {
        return { kind: "authority_conflict" } as const;
      }

      await tx`
        update app.settlement_operations
        set
          next_reconcile_at = ${input.nextReconcileAt},
          last_error_code = ${input.errorCode},
          last_chain_observed_at = ${input.observedAt},
          updated_at = ${input.observedAt}
        where settlement_id = ${input.settlementId}::uuid
      `;

      const updatedRows = await tx.unsafe(
        `${SELECT_OPERATION} where settlement_id = $1::uuid limit 1`,
        [input.settlementId],
      );
      return {
        kind: "updated",
        operation: operationFromRow(updatedRows[0] as unknown as OperationRow),
      } as const;
    });
  }

  async markAuthorityConflict(
    input: Parameters<SettlementRepository["markAuthorityConflict"]>[0],
  ): Promise<SettlementMutationResult> {
    return await this.sql.begin(async (tx) => {
      const rows = await tx.unsafe(
        `${SELECT_OPERATION} where settlement_id = $1::uuid limit 1 for update`,
        [input.settlementId],
      );
      if (!rows[0]) return { kind: "not_found" } as const;
      const current = operationFromRow(rows[0] as unknown as OperationRow);
      if (current.status === "FAILED") {
        return { kind: "replayed", operation: current } as const;
      }
      if (current.status === "FINALIZED") {
        return { kind: "authority_conflict" } as const;
      }

      await tx`
        update app.settlement_operations
        set
          status = 'FAILED',
          next_reconcile_at = null,
          last_chain_observed_at = ${input.observedAt},
          last_error_code = ${input.errorCode},
          updated_at = ${input.observedAt}
        where settlement_id = ${input.settlementId}::uuid
      `;

      await tx`
        update app.refresh_requests
        set
          status = case
            when status = 'COMPLETED' then status
            else 'FAILED'::app.refresh_status
          end,
          updated_at = ${input.observedAt},
          revision = revision + 1
        where refresh_id = ${current.refreshId}::uuid
      `;

      await tx`
        insert into app.domain_events(
          event_id,
          entity_type,
          entity_id,
          event_type,
          operation_id,
          payload,
          occurred_at
        ) values (
          ${crypto.randomUUID()}::uuid,
          'settlement',
          ${current.settlementId}::uuid,
          'SETTLEMENT_AUTHORITY_CONFLICT',
          ${current.operationId}::uuid,
          ${JSON.stringify({ error_code: input.errorCode })}::jsonb,
          ${input.observedAt}
        )
      `;

      const updatedRows = await tx.unsafe(
        `${SELECT_OPERATION} where settlement_id = $1::uuid limit 1`,
        [input.settlementId],
      );
      return {
        kind: "updated",
        operation: operationFromRow(updatedRows[0] as unknown as OperationRow),
      } as const;
    });
  }
}
