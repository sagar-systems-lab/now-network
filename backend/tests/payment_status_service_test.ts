import { ApiFault } from "../src/errors.ts";
import type {
  PaymentStatusRecord,
  PaymentStatusRepository,
} from "../src/payment-status-repository.ts";
import { PaymentStatusService } from "../src/payment-status-service.ts";

const NOW = new Date("2026-09-29T10:00:00.000Z");

function record(
  overrides: Partial<PaymentStatusRecord> = {},
): PaymentStatusRecord {
  return {
    refreshId: "71000000-0000-4000-8000-000000000001",
    verificationResultId: "72000000-0000-4000-8000-000000000001",
    refreshStatus: "VERIFIED",
    verificationCompletedAt: NOW,
    settlementId: null,
    settlementStatus: null,
    chainSignature: null,
    chainCommitment: null,
    updatedAt: null,
    confirmedAt: null,
    finalizedAt: null,
    ...overrides,
  };
}

class MemoryRepository implements PaymentStatusRepository {
  constructor(private readonly value: PaymentStatusRecord | null) {}

  getForActor(): Promise<PaymentStatusRecord | null> {
    return Promise.resolve(this.value);
  }
}

function code(error: unknown): string {
  return error instanceof ApiFault ? error.code : "";
}

Deno.test("verified refresh is payment pending before worker creates settlement", async () => {
  const service = new PaymentStatusService(new MemoryRepository(record()));
  const data = await service.get(
    { actorId: "actor", status: "ACTIVE", revision: 1 },
    record().refreshId,
  );

  if (
    data.payment_status !== "PENDING" ||
    data.settlement_status !== "NOT_STARTED" ||
    data.settlement_id !== null
  ) {
    throw new Error("pre-settlement verified state did not remain pending");
  }
});

Deno.test("ambiguous settlement stays verifying instead of failing", async () => {
  const service = new PaymentStatusService(
    new MemoryRepository(
      record({
        refreshStatus: "SETTLEMENT_VERIFYING",
        settlementId: "73000000-0000-4000-8000-000000000001",
        settlementStatus: "VERIFYING",
        chainSignature: "signature",
        updatedAt: NOW,
      }),
    ),
  );
  const data = await service.get(
    { actorId: "actor", status: "ACTIVE", revision: 1 },
    record().refreshId,
  );

  if (data.payment_status !== "VERIFYING") {
    throw new Error("ambiguous settlement was not exposed as verifying");
  }
});

Deno.test("confirmed settlement is paid before archival finalization", async () => {
  const service = new PaymentStatusService(
    new MemoryRepository(
      record({
        refreshStatus: "COMPLETED",
        settlementId: "73000000-0000-4000-8000-000000000001",
        settlementStatus: "CONFIRMED",
        chainSignature: "signature",
        chainCommitment: "confirmed",
        updatedAt: NOW,
        confirmedAt: NOW,
      }),
    ),
  );
  const data = await service.get(
    { actorId: "actor", status: "ACTIVE", revision: 1 },
    record().refreshId,
  );

  if (data.payment_status !== "PAID" || data.chain_commitment !== "confirmed") {
    throw new Error("confirmed settlement did not become paid");
  }
});

Deno.test("paid state fails closed without confirmed chain authority", async () => {
  const service = new PaymentStatusService(
    new MemoryRepository(
      record({
        refreshStatus: "COMPLETED",
        settlementId: "73000000-0000-4000-8000-000000000001",
        settlementStatus: "CONFIRMED",
        chainSignature: null,
        chainCommitment: null,
        updatedAt: NOW,
        confirmedAt: null,
      }),
    ),
  );

  const error = await service.get(
    { actorId: "actor", status: "ACTIVE", revision: 1 },
    record().refreshId,
  ).then(() => null).catch((caught) => caught);

  if (!(error instanceof Error) || error instanceof ApiFault) {
    throw new Error("malformed paid state did not fail as an invariant");
  }
});

Deno.test("proven absent settlement returns to pending product state", async () => {
  const service = new PaymentStatusService(
    new MemoryRepository(
      record({
        refreshStatus: "SETTLEMENT_PENDING",
        settlementId: "73000000-0000-4000-8000-000000000001",
        settlementStatus: "NOT_SETTLED",
        chainSignature: "signature-old",
        updatedAt: NOW,
      }),
    ),
  );
  const data = await service.get(
    { actorId: "actor", status: "ACTIVE", revision: 1 },
    record().refreshId,
  );

  if (
    data.payment_status !== "PENDING" ||
    data.settlement_status !== "NOT_SETTLED"
  ) {
    throw new Error("proven-absent settlement did not return to pending");
  }
});

Deno.test("finalized settlement is paid only with finalized authority", async () => {
  const service = new PaymentStatusService(
    new MemoryRepository(
      record({
        refreshStatus: "COMPLETED",
        settlementId: "73000000-0000-4000-8000-000000000001",
        settlementStatus: "FINALIZED",
        chainSignature: "signature-final",
        chainCommitment: "finalized",
        updatedAt: NOW,
        confirmedAt: NOW,
        finalizedAt: NOW,
      }),
    ),
  );
  const data = await service.get(
    { actorId: "actor", status: "ACTIVE", revision: 1 },
    record().refreshId,
  );

  if (
    data.payment_status !== "PAID" ||
    data.chain_commitment !== "finalized" ||
    data.finalized_at !== NOW.toISOString()
  ) {
    throw new Error("finalized settlement did not expose finalized paid authority");
  }
});

Deno.test("malformed finalized settlement fails closed", async () => {
  const service = new PaymentStatusService(
    new MemoryRepository(
      record({
        refreshStatus: "COMPLETED",
        settlementId: "73000000-0000-4000-8000-000000000001",
        settlementStatus: "FINALIZED",
        chainSignature: "signature-final",
        chainCommitment: "confirmed",
        updatedAt: NOW,
        confirmedAt: NOW,
        finalizedAt: null,
      }),
    ),
  );

  const error = await service.get(
    { actorId: "actor", status: "ACTIVE", revision: 1 },
    record().refreshId,
  ).then(() => null).catch((caught) => caught);

  if (!(error instanceof Error) || error instanceof ApiFault) {
    throw new Error("malformed finalized state did not fail as an invariant");
  }
});

Deno.test("unauthorized payment state remains hidden", async () => {
  const service = new PaymentStatusService(new MemoryRepository(null));
  try {
    await service.get(
      { actorId: "actor", status: "ACTIVE", revision: 1 },
      record().refreshId,
    );
    throw new Error("unauthorized payment status unexpectedly returned");
  } catch (error) {
    if (code(error) !== "PAYMENT_NOT_FOUND") throw error;
  }
});
