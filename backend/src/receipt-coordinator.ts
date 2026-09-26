import { deriveReceiptDigestV1 } from "./receipt-identity.ts";
import type { ReceiptAuthority, ReceiptRecord, ReceiptRepository } from "./receipt-repository.ts";

export type ReceiptTickSummary = {
  finalized: number;
  replayed: number;
  conflicts: number;
};

function assertAuthority(authority: ReceiptAuthority): void {
  if (
    authority.settlementOperationHash.length !== 32 ||
    authority.verificationDigest.length !== 32 ||
    authority.rewardAmountAtomic <= 0n ||
    authority.settlementSignature.trim().length === 0 ||
    authority.chainCommitment !== "finalized"
  ) {
    throw new Error("receipt authority is incomplete");
  }
}

export class ReceiptCoordinator {
  constructor(
    private readonly repository: ReceiptRepository,
    private readonly now: () => Date = () => new Date(),
  ) {}

  async finalizeOne(
    authority: ReceiptAuthority,
  ): Promise<
    | { kind: "created" | "replayed"; receipt: ReceiptRecord }
    | { kind: "not_finalizable" | "authority_conflict" }
  > {
    try {
      assertAuthority(authority);
    } catch {
      return { kind: "authority_conflict" };
    }
    const receiptDigest = await deriveReceiptDigestV1({
      ...authority,
      chainCommitment: "finalized",
    });
    const result = await this.repository.finalize({
      authority,
      receiptId: crypto.randomUUID(),
      receiptDigest,
      observedAt: this.now(),
    });
    return result;
  }

  async runOnce(limit = 8): Promise<ReceiptTickSummary> {
    const bounded = Math.max(1, Math.min(32, Math.trunc(limit)));
    const authorities = await this.repository.findFinalizable(bounded);
    const summary: ReceiptTickSummary = {
      finalized: 0,
      replayed: 0,
      conflicts: 0,
    };

    for (const authority of authorities) {
      const result = await this.finalizeOne(authority);
      if (result.kind === "created") summary.finalized += 1;
      else if (result.kind === "replayed") summary.replayed += 1;
      else if (result.kind === "authority_conflict") summary.conflicts += 1;
    }
    return summary;
  }
}
