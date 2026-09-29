import type { ActorRecord } from "./identity-repository.ts";
import { ApiFault } from "./errors.ts";
import type { PaymentStatusRecord, PaymentStatusRepository } from "./payment-status-repository.ts";

type ProductPaymentStatus = "PENDING" | "VERIFYING" | "PAID" | "FAILED";

function productStatus(
  status: PaymentStatusRecord["settlementStatus"],
): ProductPaymentStatus {
  switch (status) {
    case null:
    case "NOT_STARTED":
    case "ELIGIBLE":
    case "BUILDING":
    case "SUBMITTING":
    case "NOT_SETTLED":
      return "PENDING";

    case "SUBMITTED":
    case "VERIFYING":
      return "VERIFYING";

    case "CONFIRMED":
    case "FINALIZING":
    case "FINALIZED":
      return "PAID";

    case "FAILED":
      return "FAILED";
  }
}

function validate(record: PaymentStatusRecord): void {
  if (record.settlementStatus === null) {
    if (record.refreshStatus !== "VERIFIED") {
      throw new Error("verified payment authority is missing its settlement operation");
    }
    return;
  }

  const product = productStatus(record.settlementStatus);
  if (product === "PAID") {
    if (
      record.chainSignature === null ||
      !["confirmed", "finalized"].includes(record.chainCommitment ?? "") ||
      record.confirmedAt === null
    ) {
      throw new Error("paid settlement is missing confirmed chain authority");
    }
  }

  if (
    record.settlementStatus === "FINALIZED" &&
    (record.chainCommitment !== "finalized" || record.finalizedAt === null)
  ) {
    throw new Error("finalized settlement is missing final chain authority");
  }
}

export class PaymentStatusService {
  constructor(private readonly repository: PaymentStatusRepository) {}

  async get(
    actor: ActorRecord,
    refreshId: string,
  ): Promise<Record<string, unknown>> {
    if (actor.status === "DISABLED") {
      throw new ApiFault(403, "ACTOR_DISABLED", "This actor is disabled.");
    }
    if (actor.status === "RESTRICTED") {
      throw new ApiFault(403, "ACTOR_RESTRICTED", "This actor is restricted.");
    }

    const record = await this.repository.getForActor(refreshId, actor.actorId);
    if (record === null) {
      throw new ApiFault(404, "PAYMENT_NOT_FOUND", "Payment status was not found.");
    }
    validate(record);

    const settlementStatus = record.settlementStatus ?? "NOT_STARTED";
    return {
      refresh_id: record.refreshId,
      verification_result_id: record.verificationResultId,
      settlement_id: record.settlementId,
      settlement_status: settlementStatus,
      payment_status: productStatus(record.settlementStatus),
      chain_signature: record.chainSignature,
      chain_commitment: record.chainCommitment,
      confirmed_at: record.confirmedAt?.toISOString() ?? null,
      finalized_at: record.finalizedAt?.toISOString() ?? null,
      updated_at: (record.updatedAt ?? record.verificationCompletedAt).toISOString(),
    };
  }
}
