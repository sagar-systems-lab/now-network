import postgres from "npm:postgres@3.4.7";
import type {
  FinalizeReceiptResult,
  ReceiptAuthority,
  ReceiptRecord,
  ReceiptRepository,
} from "./receipt-repository.ts";

type DateLike = Date | string;

type AuthorityRow = {
  refresh_id: string;
  state_id: string;
  requester_actor_id: string;
  verification_result_id: string;
  settlement_id: string;
  settlement_operation_hash: Uint8Array;
  final_value: unknown;
  observed_at: DateLike;
  verification_class: string;
  reward_amount_atomic: number | string;
  reward_mint: string;
  verification_digest: Uint8Array;
  settlement_signature: string;
  chain_commitment: string;
  settlement_finalized_at: DateLike;
};

type ReceiptRow = {
  receipt_id: string;
  refresh_id: string;
  state_id: string;
  verification_result_id: string;
  settlement_id: string;
  status: string;
  final_value: unknown;
  observed_at: DateLike;
  verification_class: string;
  reward_amount_atomic: number | string;
  reward_mint: string;
  verification_digest: Uint8Array;
  settlement_operation_hash: Uint8Array;
  settlement_signature: string | null;
  chain_commitment: string | null;
  receipt_digest: Uint8Array;
  finalized_at: DateLike | null;
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

function authorityFromRow(row: AuthorityRow): ReceiptAuthority {
  if (
    row.settlement_operation_hash.length !== 32 ||
    row.verification_digest.length !== 32 ||
    row.chain_commitment !== "finalized" ||
    row.settlement_signature.trim().length === 0
  ) {
    throw new Error("finalized settlement receipt authority is malformed");
  }
  return {
    refreshId: row.refresh_id,
    stateId: row.state_id,
    requesterActorId: row.requester_actor_id,
    verificationResultId: row.verification_result_id,
    settlementId: row.settlement_id,
    settlementOperationHash: new Uint8Array(row.settlement_operation_hash),
    finalValue: row.final_value,
    observedAt: date(row.observed_at),
    verificationClass: row.verification_class,
    rewardAmountAtomic: BigInt(row.reward_amount_atomic),
    rewardMint: row.reward_mint,
    verificationDigest: new Uint8Array(row.verification_digest),
    settlementSignature: row.settlement_signature,
    chainCommitment: "finalized",
    settlementFinalizedAt: date(row.settlement_finalized_at),
  };
}

function receiptFromRow(row: ReceiptRow): ReceiptRecord {
  if (
    row.status !== "FINAL" ||
    row.settlement_signature === null ||
    row.chain_commitment !== "finalized" ||
    row.finalized_at === null
  ) {
    throw new Error("stored final receipt is malformed");
  }
  return {
    receiptId: row.receipt_id,
    refreshId: row.refresh_id,
    stateId: row.state_id,
    verificationResultId: row.verification_result_id,
    settlementId: row.settlement_id,
    status: "FINAL",
    finalValue: row.final_value,
    observedAt: date(row.observed_at),
    verificationClass: row.verification_class,
    rewardAmountAtomic: BigInt(row.reward_amount_atomic),
    rewardMint: row.reward_mint,
    verificationDigest: new Uint8Array(row.verification_digest),
    settlementOperationHash: new Uint8Array(row.settlement_operation_hash),
    settlementSignature: row.settlement_signature,
    chainCommitment: "finalized",
    receiptDigest: new Uint8Array(row.receipt_digest),
    finalizedAt: date(row.finalized_at),
    revision: Number(row.revision),
  };
}

const SELECT_AUTHORITY = String.raw`
  select
    rr.refresh_id,
    rr.state_id,
    rr.requester_actor_id,
    vr.verification_result_id,
    so.settlement_id,
    so.operation_hash as settlement_operation_hash,
    sh.value as final_value,
    sh.observed_at,
    sh.verification_class,
    so.locked_reward_atomic as reward_amount_atomic,
    so.reward_mint,
    so.verification_digest,
    so.chain_signature as settlement_signature,
    so.chain_commitment,
    so.finalized_at as settlement_finalized_at
  from app.settlement_operations so
  join app.refresh_requests rr
    on rr.refresh_id = so.refresh_id
  join app.verification_results vr
    on vr.verification_result_id = so.verification_result_id
   and vr.refresh_id = rr.refresh_id
  join app.state_history sh
    on sh.refresh_id = rr.refresh_id
   and sh.verification_result_id = vr.verification_result_id
  where so.status = 'FINALIZED'
    and so.chain_commitment = 'finalized'
    and so.chain_signature is not null
    and so.finalized_at is not null
    and rr.status = 'COMPLETED'
    and octet_length(so.operation_hash) = 32
    and octet_length(so.verification_digest) = 32
    and so.verification_digest = vr.canonical_digest
    and sh.value is not null
`;

const SELECT_RECEIPT = String.raw`
  select
    receipt_id,
    refresh_id,
    state_id,
    verification_result_id,
    settlement_id,
    status,
    final_value,
    observed_at,
    verification_class,
    reward_amount_atomic,
    reward_mint,
    verification_digest,
    settlement_operation_hash,
    settlement_signature,
    chain_commitment,
    receipt_digest,
    finalized_at,
    revision
  from app.receipts
`;

function authorityEqual(
  left: ReceiptAuthority,
  right: ReceiptAuthority,
): boolean {
  return left.refreshId === right.refreshId &&
    left.stateId === right.stateId &&
    left.requesterActorId === right.requesterActorId &&
    left.verificationResultId === right.verificationResultId &&
    left.settlementId === right.settlementId &&
    bytesEqual(left.settlementOperationHash, right.settlementOperationHash) &&
    JSON.stringify(left.finalValue) === JSON.stringify(right.finalValue) &&
    left.observedAt.getTime() === right.observedAt.getTime() &&
    left.verificationClass === right.verificationClass &&
    left.rewardAmountAtomic === right.rewardAmountAtomic &&
    left.rewardMint === right.rewardMint &&
    bytesEqual(left.verificationDigest, right.verificationDigest) &&
    left.settlementSignature === right.settlementSignature &&
    left.chainCommitment === right.chainCommitment &&
    left.settlementFinalizedAt.getTime() ===
      right.settlementFinalizedAt.getTime();
}

export class PostgresReceiptRepository implements ReceiptRepository {
  private readonly sql: ReturnType<typeof postgres>;

  constructor(connectionString: string) {
    this.sql = postgres(connectionString, {
      max: 1,
      prepare: false,
      idle_timeout: 20,
      connect_timeout: 10,
    });
  }

  async findFinalizable(limit: number): Promise<ReceiptAuthority[]> {
    const bounded = Math.max(1, Math.min(32, Math.trunc(limit)));
    const rows = await this.sql.unsafe(
      `${SELECT_AUTHORITY}
       and not exists (
         select 1
         from app.receipts r
         where r.refresh_id = rr.refresh_id
       )
       order by so.finalized_at asc, so.settlement_id asc
       limit $1`,
      [bounded],
    );
    return rows.map((row) => authorityFromRow(row as unknown as AuthorityRow));
  }

  async finalize(
    input: Parameters<ReceiptRepository["finalize"]>[0],
  ): Promise<FinalizeReceiptResult> {
    return await this.sql.begin(async (tx) => {
      await tx`
        select pg_advisory_xact_lock(
          hashtextextended(${input.authority.refreshId}, 81)
        )
      `;

      const existingRows = await tx.unsafe(
        `${SELECT_RECEIPT} where refresh_id = $1::uuid limit 1`,
        [input.authority.refreshId],
      );
      if (existingRows[0]) {
        const receipt = receiptFromRow(
          existingRows[0] as unknown as ReceiptRow,
        );
        if (
          receipt.settlementId !== input.authority.settlementId ||
          !bytesEqual(receipt.receiptDigest, input.receiptDigest)
        ) {
          return { kind: "authority_conflict" } as const;
        }
        return { kind: "replayed", receipt } as const;
      }

      const authorityRows = await tx.unsafe(
        `${SELECT_AUTHORITY}
         and rr.refresh_id = $1::uuid
         and so.settlement_id = $2::uuid
         and vr.verification_result_id = $3::uuid
         limit 1
         for update of so, rr, vr`,
        [
          input.authority.refreshId,
          input.authority.settlementId,
          input.authority.verificationResultId,
        ],
      );
      if (!authorityRows[0]) return { kind: "not_finalizable" } as const;
      const locked = authorityFromRow(
        authorityRows[0] as unknown as AuthorityRow,
      );
      if (!authorityEqual(locked, input.authority)) {
        return { kind: "authority_conflict" } as const;
      }

      await tx`
        insert into app.receipts(
          receipt_id,
          refresh_id,
          state_id,
          verification_result_id,
          settlement_id,
          status,
          final_value,
          observed_at,
          verification_class,
          reward_amount_atomic,
          reward_mint,
          verification_digest,
          settlement_operation_hash,
          settlement_signature,
          chain_commitment,
          receipt_digest,
          created_at,
          updated_at,
          finalized_at,
          revision
        ) values (
          ${input.receiptId}::uuid,
          ${locked.refreshId}::uuid,
          ${locked.stateId}::uuid,
          ${locked.verificationResultId}::uuid,
          ${locked.settlementId}::uuid,
          'FINAL',
          ${JSON.stringify(locked.finalValue)}::jsonb,
          ${locked.observedAt},
          ${locked.verificationClass}::app.verification_class,
          ${locked.rewardAmountAtomic.toString()}::numeric,
          ${locked.rewardMint},
          ${locked.verificationDigest},
          ${locked.settlementOperationHash},
          ${locked.settlementSignature},
          'finalized',
          ${input.receiptDigest},
          ${input.observedAt},
          ${input.observedAt},
          ${locked.settlementFinalizedAt},
          1
        )
      `;

      await tx`
        insert into app.domain_events(
          event_id,
          entity_type,
          entity_id,
          event_type,
          correlation_id,
          entity_revision,
          payload,
          occurred_at
        ) values (
          ${crypto.randomUUID()}::uuid,
          'receipt',
          ${input.receiptId}::uuid,
          'RECEIPT_FINALIZED',
          ${locked.refreshId}::uuid,
          1,
          ${
        JSON.stringify({
          receipt_id: input.receiptId,
          refresh_id: locked.refreshId,
          state_id: locked.stateId,
        })
      }::jsonb,
          ${input.observedAt}
        )
      `;

      const createdRows = await tx.unsafe(
        `${SELECT_RECEIPT} where receipt_id = $1::uuid limit 1`,
        [input.receiptId],
      );
      return {
        kind: "created",
        receipt: receiptFromRow(createdRows[0] as unknown as ReceiptRow),
      } as const;
    });
  }

  async getForActor(
    refreshId: string,
    actorId: string,
  ): Promise<ReceiptRecord | null> {
    const rows = await this.sql.unsafe(
      `select
         r.receipt_id,
         r.refresh_id,
         r.state_id,
         r.verification_result_id,
         r.settlement_id,
         r.status,
         r.final_value,
         r.observed_at,
         r.verification_class,
         r.reward_amount_atomic,
         r.reward_mint,
         r.verification_digest,
         r.settlement_operation_hash,
         r.settlement_signature,
         r.chain_commitment,
         r.receipt_digest,
         r.finalized_at,
         r.revision
       from app.receipts r
       join app.refresh_requests rr
         on rr.refresh_id = r.refresh_id
       join app.settlement_operations so
         on so.settlement_id = r.settlement_id
       where r.refresh_id = $1::uuid
         and (
           rr.requester_actor_id = $2::uuid
           or exists (
             select 1
             from app.wallet_bindings wb
             where wb.actor_id = $2::uuid
               and wb.wallet_address = any(so.recipient_wallets)
           )
         )
       limit 1`,
      [refreshId, actorId],
    );
    return rows[0] ? receiptFromRow(rows[0] as unknown as ReceiptRow) : null;
  }
}
