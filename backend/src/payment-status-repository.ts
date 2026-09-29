import type { RefreshStatus, SettlementStatus } from "../../packages/contracts/src/lifecycle.ts";

export type PaymentStatusRecord = {
  refreshId: string;
  verificationResultId: string;
  refreshStatus: RefreshStatus;
  verificationCompletedAt: Date;
  settlementId: string | null;
  settlementStatus: SettlementStatus | null;
  chainSignature: string | null;
  chainCommitment: string | null;
  updatedAt: Date | null;
  confirmedAt: Date | null;
  finalizedAt: Date | null;
};

export interface PaymentStatusRepository {
  getForActor(
    refreshId: string,
    actorId: string,
  ): Promise<PaymentStatusRecord | null>;
}
