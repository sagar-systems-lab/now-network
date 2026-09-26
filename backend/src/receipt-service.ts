import type { ActorRecord } from "./identity-repository.ts";
import { ApiFault } from "./errors.ts";
import type { ReceiptRecord, ReceiptRepository } from "./receipt-repository.ts";

function receiptData(receipt: ReceiptRecord): Record<string, unknown> {
  return {
    receipt_id: receipt.receiptId,
    refresh_id: receipt.refreshId,
    state_id: receipt.stateId,
    verification_result_id: receipt.verificationResultId,
    settlement_id: receipt.settlementId,
    status: receipt.status,
    final_value: receipt.finalValue,
    observed_at: receipt.observedAt.toISOString(),
    verification_class: receipt.verificationClass,
    reward_amount_atomic: receipt.rewardAmountAtomic.toString(),
    reward_mint: receipt.rewardMint,
    settlement_signature: receipt.settlementSignature,
    chain_commitment: receipt.chainCommitment,
    finalized_at: receipt.finalizedAt.toISOString(),
    revision: receipt.revision,
  };
}

export class ReceiptService {
  constructor(private readonly repository: ReceiptRepository) {}

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

    const receipt = await this.repository.getForActor(refreshId, actor.actorId);
    if (receipt === null) {
      throw new ApiFault(404, "RECEIPT_NOT_FOUND", "Receipt was not found.");
    }
    return receiptData(receipt);
  }
}
