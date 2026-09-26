import { ReceiptCoordinator } from "../src/receipt-coordinator.ts";
import type {
  FinalizeReceiptResult,
  ReceiptAuthority,
  ReceiptRecord,
  ReceiptRepository,
} from "../src/receipt-repository.ts";

const NOW = new Date("2026-09-26T06:02:00.000Z");

function authority(): ReceiptAuthority {
  return {
    refreshId: "b1000000-0000-4000-8000-000000000001",
    stateId: "b2000000-0000-4000-8000-000000000001",
    requesterActorId: "b3000000-0000-4000-8000-000000000001",
    verificationResultId: "b4000000-0000-4000-8000-000000000001",
    settlementId: "b5000000-0000-4000-8000-000000000001",
    settlementOperationHash: new Uint8Array(32).fill(4),
    finalValue: {
      kind: "numeric",
      scaled_value: "2",
      scale: 0,
      unit: "spaces",
    },
    observedAt: new Date("2026-09-26T06:00:00.000Z"),
    verificationClass: "FAST",
    rewardAmountAtomic: 500_000n,
    rewardMint: "So11111111111111111111111111111111111111112",
    verificationDigest: new Uint8Array(32).fill(5),
    settlementSignature: "settlement-final-signature",
    chainCommitment: "finalized",
    settlementFinalizedAt: new Date("2026-09-26T06:01:00.000Z"),
  };
}

class MemoryReceiptRepository implements ReceiptRepository {
  receipt: ReceiptRecord | null = null;
  candidate: ReceiptAuthority | null = authority();
  finalizeCalls = 0;

  findFinalizable(): Promise<ReceiptAuthority[]> {
    return Promise.resolve(this.candidate ? [structuredClone(this.candidate)] : []);
  }

  finalize(
    input: Parameters<ReceiptRepository["finalize"]>[0],
  ): Promise<FinalizeReceiptResult> {
    this.finalizeCalls += 1;
    if (this.receipt !== null) {
      return Promise.resolve({ kind: "replayed", receipt: this.receipt });
    }
    this.receipt = {
      receiptId: input.receiptId,
      refreshId: input.authority.refreshId,
      stateId: input.authority.stateId,
      verificationResultId: input.authority.verificationResultId,
      settlementId: input.authority.settlementId,
      status: "FINAL",
      finalValue: input.authority.finalValue,
      observedAt: input.authority.observedAt,
      verificationClass: input.authority.verificationClass,
      rewardAmountAtomic: input.authority.rewardAmountAtomic,
      rewardMint: input.authority.rewardMint,
      verificationDigest: input.authority.verificationDigest,
      settlementOperationHash: input.authority.settlementOperationHash,
      settlementSignature: input.authority.settlementSignature,
      chainCommitment: "finalized",
      receiptDigest: input.receiptDigest,
      finalizedAt: input.authority.settlementFinalizedAt,
      revision: 1,
    };
    return Promise.resolve({ kind: "created", receipt: this.receipt });
  }

  getForActor(): Promise<ReceiptRecord | null> {
    return Promise.resolve(this.receipt);
  }
}

Deno.test("receipt coordinator creates final receipt only from finalized authority", async () => {
  const repository = new MemoryReceiptRepository();
  const coordinator = new ReceiptCoordinator(repository, () => NOW);

  const summary = await coordinator.runOnce(8);
  if (
    summary.finalized !== 1 ||
    summary.replayed !== 0 ||
    summary.conflicts !== 0 ||
    repository.receipt?.receiptDigest.length !== 32
  ) {
    throw new Error("final receipt was not created deterministically");
  }
});

Deno.test("receipt coordinator reports idempotent replay without duplicate finalization", async () => {
  const repository = new MemoryReceiptRepository();
  const coordinator = new ReceiptCoordinator(repository, () => NOW);

  await coordinator.runOnce(8);
  const second = await coordinator.runOnce(8);
  if (
    second.finalized !== 0 ||
    second.replayed !== 1 ||
    repository.finalizeCalls !== 2
  ) {
    throw new Error("receipt replay was not reported idempotently");
  }
});

Deno.test("receipt coordinator fails closed on malformed settlement authority", async () => {
  const repository = new MemoryReceiptRepository();
  repository.candidate = {
    ...authority(),
    verificationDigest: new Uint8Array(31),
  };
  const coordinator = new ReceiptCoordinator(repository, () => NOW);

  const summary = await coordinator.runOnce(8);
  if (summary.conflicts !== 1 || repository.finalizeCalls !== 0) {
    throw new Error("malformed receipt authority reached persistence");
  }
});
