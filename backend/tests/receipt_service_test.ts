import { ApiFault } from "../src/errors.ts";
import { ReceiptService } from "../src/receipt-service.ts";
import type {
  FinalizeReceiptResult,
  ReceiptAuthority,
  ReceiptRecord,
  ReceiptRepository,
} from "../src/receipt-repository.ts";

const RECEIPT: ReceiptRecord = {
  receiptId: "c1000000-0000-4000-8000-000000000001",
  refreshId: "c2000000-0000-4000-8000-000000000001",
  stateId: "c3000000-0000-4000-8000-000000000001",
  verificationResultId: "c4000000-0000-4000-8000-000000000001",
  settlementId: "c5000000-0000-4000-8000-000000000001",
  status: "FINAL",
  finalValue: { kind: "binary", value: "OPEN" },
  observedAt: new Date("2026-09-26T06:00:00.000Z"),
  verificationClass: "FAST",
  rewardAmountAtomic: 100n,
  rewardMint: "mint",
  verificationDigest: new Uint8Array(32).fill(2),
  settlementOperationHash: new Uint8Array(32).fill(3),
  settlementSignature: "signature",
  chainCommitment: "finalized",
  receiptDigest: new Uint8Array(32).fill(4),
  finalizedAt: new Date("2026-09-26T06:01:00.000Z"),
  revision: 1,
};

class ReadRepository implements ReceiptRepository {
  constructor(private readonly visible: boolean) {}

  findFinalizable(): Promise<ReceiptAuthority[]> {
    return Promise.resolve([]);
  }

  finalize(): Promise<FinalizeReceiptResult> {
    return Promise.resolve({ kind: "not_finalizable" });
  }

  getForActor(): Promise<ReceiptRecord | null> {
    return Promise.resolve(this.visible ? RECEIPT : null);
  }
}

function code(error: unknown): string {
  return error instanceof ApiFault ? error.code : "";
}

Deno.test("receipt service returns finalized payment proof to an authorized actor", async () => {
  const service = new ReceiptService(new ReadRepository(true));
  const data = await service.get(
    { actorId: "actor", status: "ACTIVE", revision: 1 },
    RECEIPT.refreshId,
  );
  if (
    data.receipt_id !== RECEIPT.receiptId ||
    data.chain_commitment !== "finalized" ||
    data.reward_amount_atomic !== "100"
  ) {
    throw new Error("receipt response lost finalized settlement proof");
  }
});

Deno.test("receipt service does not reveal receipt existence to unauthorized actor", async () => {
  const service = new ReceiptService(new ReadRepository(false));
  try {
    await service.get(
      { actorId: "actor", status: "ACTIVE", revision: 1 },
      RECEIPT.refreshId,
    );
    throw new Error("unauthorized receipt unexpectedly returned");
  } catch (error) {
    if (code(error) !== "RECEIPT_NOT_FOUND") throw error;
  }
});
