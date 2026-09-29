import postgres from "npm:postgres@3.4.7";
import type { PaymentStatusRecord, PaymentStatusRepository } from "./payment-status-repository.ts";
import type { RefreshStatus, SettlementStatus } from "../../packages/contracts/src/lifecycle.ts";

type DateLike = Date | string;

type PaymentRow = {
  refresh_id: string;
  verification_result_id: string;
  refresh_status: RefreshStatus;
  verification_completed_at: DateLike;
  settlement_id: string | null;
  settlement_status: SettlementStatus | null;
  chain_signature: string | null;
  chain_commitment: string | null;
  settlement_updated_at: DateLike | null;
  confirmed_at: DateLike | null;
  finalized_at: DateLike | null;
};

function date(value: DateLike): Date {
  return value instanceof Date ? value : new Date(value);
}

export class PostgresPaymentStatusRepository implements PaymentStatusRepository {
  private readonly sql: ReturnType<typeof postgres>;

  constructor(connectionString: string) {
    this.sql = postgres(connectionString, {
      max: 1,
      prepare: false,
      idle_timeout: 20,
      connect_timeout: 10,
    });
  }

  async getForActor(
    refreshId: string,
    actorId: string,
  ): Promise<PaymentStatusRecord | null> {
    const rows = await this.sql`
      select
        rr.refresh_id,
        vr.verification_result_id,
        rr.status as refresh_status,
        vr.completed_at as verification_completed_at,
        so.settlement_id,
        so.status as settlement_status,
        so.chain_signature,
        so.chain_commitment,
        so.updated_at as settlement_updated_at,
        so.confirmed_at,
        so.finalized_at
      from app.refresh_requests rr
      join lateral (
        select
          candidate.verification_result_id,
          candidate.evidence_ids,
          candidate.completed_at
        from app.verification_results candidate
        where candidate.refresh_id = rr.refresh_id
          and candidate.status = 'VERIFIED'
          and candidate.completed_at is not null
        order by candidate.completed_at desc, candidate.verification_result_id desc
        limit 1
      ) vr on true
      left join app.settlement_operations so
        on so.refresh_id = rr.refresh_id
       and so.verification_result_id = vr.verification_result_id
      where rr.refresh_id = ${refreshId}::uuid
        and (
          rr.requester_actor_id = ${actorId}::uuid
          or exists (
            select 1
            from app.evidence_packets ep
            where ep.refresh_id = rr.refresh_id
              and ep.actor_id = ${actorId}::uuid
              and ep.status = 'VERIFIED'
              and ep.evidence_id = any(vr.evidence_ids)
          )
        )
      limit 1
    `;
    if (!rows[0]) return null;
    const row = rows[0] as unknown as PaymentRow;

    return {
      refreshId: row.refresh_id,
      verificationResultId: row.verification_result_id,
      refreshStatus: row.refresh_status,
      verificationCompletedAt: date(row.verification_completed_at),
      settlementId: row.settlement_id,
      settlementStatus: row.settlement_status,
      chainSignature: row.chain_signature,
      chainCommitment: row.chain_commitment,
      updatedAt: row.settlement_updated_at === null ? null : date(row.settlement_updated_at),
      confirmedAt: row.confirmed_at === null ? null : date(row.confirmed_at),
      finalizedAt: row.finalized_at === null ? null : date(row.finalized_at),
    };
  }
}
