import { ApiFault } from "../src/errors.ts";
import type { ActorRecord } from "../src/identity-repository.ts";
import type {
  PersistVerificationResult,
  VerificationContext,
  VerificationRepository,
} from "../src/verification-repository.ts";
import { VerificationService } from "../src/verification-service.ts";

const ACTOR_ID = "d0000000-0000-4000-8000-000000000001";
const OTHER_ACTOR_ID = "d0000000-0000-4000-8000-000000000002";
const REFRESH_ID = "d1000000-0000-4000-8000-000000000001";
const EVIDENCE_ID = "d2000000-0000-4000-8000-000000000001";
const EVIDENCE_ID_2 = "d2000000-0000-4000-8000-000000000002";
const NOW = new Date("2026-09-25T12:00:00.000Z");

function actor(actorId = ACTOR_ID): ActorRecord {
  return { actorId, status: "ACTIVE", revision: 1 };
}

function evidence(
  evidenceId: string,
  answerValue: number,
  committedOffsetMs = 0,
): VerificationContext["evidence"][number] {
  return {
    evidenceId,
    actorId: ACTOR_ID,
    answerType: "NUMERIC",
    answerValue,
    intentCoreHash: new Uint8Array(32).fill(1),
    executionHash: new Uint8Array(32).fill(2),
    mediaSha256: new Uint8Array(32).fill(3),
    mediaSizeBytes: 100,
    mediaMime: "image/jpeg",
    locationSampleCount: 1,
    serverObservationEarliest: new Date("2026-09-25T11:59:00.000Z"),
    serverObservationLatest: new Date("2026-09-25T11:59:30.000Z"),
    committedAt: new Date(NOW.getTime() + committedOffsetMs),
    status: "COMMITTED",
  };
}

function context(
  overrides: Partial<VerificationContext> = {},
): VerificationContext {
  return {
    refreshId: REFRESH_ID,
    requesterActorId: ACTOR_ID,
    refreshStatus: "EVIDENCE_SUBMITTED",
    refreshRevision: 12,
    refreshExpiresAt: new Date("2026-09-25T12:10:00.000Z"),
    evidenceDeadline: new Date("2026-09-25T12:05:00.000Z"),
    stateId: "d3000000-0000-4000-8000-000000000001",
    stateVersion: 1,
    stateType: "NUMERIC",
    intentCoreHash: new Uint8Array(32).fill(1),
    executionHash: new Uint8Array(32).fill(2),
    proofPolicySnapshot: {
      template_key: "parking.available_spaces.v1",
      state_type: "NUMERIC",
      fresh_ttl_seconds: 600,
      aging_ratio: 0.7,
      verification_class: "FAST",
      required_witnesses: 1,
      capture: {
        media_required: true,
        location_required: true,
      },
      numeric: {
        scale: 0,
        min: 0,
        conflict_tolerance: 1,
        allow_two_of_three: true,
      },
    },
    evidence: [evidence(EVIDENCE_ID, 2)],
    existing: null,
    ...overrides,
  };
}

class MemoryVerificationRepository implements VerificationRepository {
  persistCalls = 0;

  constructor(public value: VerificationContext | null) {}

  getContext(): Promise<VerificationContext | null> {
    return Promise.resolve(this.value ? structuredClone(this.value) : null);
  }

  persist(
    input: Parameters<VerificationRepository["persist"]>[0],
  ): Promise<PersistVerificationResult> {
    this.persistCalls += 1;
    const refreshStatus = input.outcome.result === "VERIFIED"
      ? "VERIFIED"
      : input.outcome.result === "CONFLICT"
      ? "ADDITIONAL_VERIFICATION"
      : input.outcome.result === "REQUIRES_ADDITIONAL_VERIFICATION"
      ? "ADDITIONAL_VERIFICATION"
      : input.outcome.result === "EXPIRED"
      ? "EXPIRED"
      : "CLAIMED";

    return Promise.resolve({
      kind: "recorded",
      verificationResultId: input.verificationResultId,
      refreshId: input.refreshId,
      evidenceSetRevision: input.evidenceSetRevision,
      policyVersion: input.policyVersion,
      result: input.outcome.result,
      status: input.outcome.status,
      reasonCodes: [...input.outcome.reasonCodes],
      evidenceIds: [...input.evidenceIds],
      finalAnswer: input.outcome.finalAnswer,
      canonicalDigest: input.canonicalDigest,
      refreshStatus,
      refreshRevision: 14,
      completedAt: input.observedAt,
    });
  }
}

function faultCode(error: unknown): string {
  return error instanceof ApiFault ? error.code : "";
}

Deno.test("verification accepts a policy-valid numeric evidence set", async () => {
  const repository = new MemoryVerificationRepository(context());
  const service = new VerificationService(
    repository,
    "verification-test-v1",
    () => NOW,
  );

  const result = await service.verify(actor(), REFRESH_ID);
  if (
    result.status !== 201 ||
    result.data.result !== "VERIFIED" ||
    result.data.status !== "VERIFIED" ||
    result.data.final_answer !== 2 ||
    result.data.refresh_status !== "VERIFIED" ||
    result.data.next_step !== "PROJECTION" ||
    repository.persistCalls !== 1
  ) {
    throw new Error("verification did not accept the valid evidence set");
  }
});

Deno.test("verification exposes conflicting numeric reports", async () => {
  const value = context({
    proofPolicySnapshot: {
      ...context().proofPolicySnapshot,
      verification_class: "CORROBORATED",
      required_witnesses: 2,
    },
    evidence: [
      evidence(EVIDENCE_ID, 2),
      evidence(EVIDENCE_ID_2, 5, 1_000),
    ],
  });
  const repository = new MemoryVerificationRepository(value);
  const service = new VerificationService(
    repository,
    "verification-test-v1",
    () => NOW,
  );

  const result = await service.verify(actor(), REFRESH_ID);
  if (
    result.data.result !== "CONFLICT" ||
    result.data.status !== "CONFLICT" ||
    result.data.refresh_status !== "ADDITIONAL_VERIFICATION" ||
    result.data.next_step !== "ADDITIONAL_VERIFICATION"
  ) {
    throw new Error("conflicting evidence was not exposed as conflict");
  }
});

Deno.test("verification requests more evidence when policy witness count is unmet", async () => {
  const value = context({
    proofPolicySnapshot: {
      ...context().proofPolicySnapshot,
      verification_class: "CORROBORATED",
      required_witnesses: 2,
    },
  });
  const repository = new MemoryVerificationRepository(value);
  const service = new VerificationService(
    repository,
    "verification-test-v1",
    () => NOW,
  );

  const result = await service.verify(actor(), REFRESH_ID);
  if (
    result.data.result !== "REQUIRES_ADDITIONAL_VERIFICATION" ||
    result.data.status !== "WAITING_FOR_MORE_EVIDENCE" ||
    result.data.refresh_status !== "ADDITIONAL_VERIFICATION"
  ) {
    throw new Error("insufficient witnesses did not request more evidence");
  }
});

Deno.test("verification hides a refresh from unrelated actors", async () => {
  const repository = new MemoryVerificationRepository(context());
  const service = new VerificationService(
    repository,
    "verification-test-v1",
    () => NOW,
  );

  try {
    await service.verify(actor(OTHER_ACTOR_ID), REFRESH_ID);
    throw new Error("unrelated actor unexpectedly triggered verification");
  } catch (error) {
    if (faultCode(error) !== "REFRESH_NOT_FOUND") throw error;
  }
  if (repository.persistCalls !== 0) {
    throw new Error("verification persisted after actor mismatch");
  }
});

Deno.test("verification fails closed on execution identity drift", async () => {
  const value = context({
    evidence: [{
      ...evidence(EVIDENCE_ID, 2),
      executionHash: new Uint8Array(32).fill(9),
    }],
  });
  const repository = new MemoryVerificationRepository(value);
  const service = new VerificationService(
    repository,
    "verification-test-v1",
    () => NOW,
  );

  try {
    await service.verify(actor(), REFRESH_ID);
    throw new Error("execution drift unexpectedly verified");
  } catch (error) {
    if (faultCode(error) !== "VERIFICATION_NOT_ELIGIBLE") throw error;
  }
  if (repository.persistCalls !== 0) {
    throw new Error("verification persisted after execution drift");
  }
});

Deno.test("verification replays the same frozen evidence result without mutation", async () => {
  const value = context({
    refreshStatus: "VERIFIED",
    evidence: [{
      ...evidence(EVIDENCE_ID, 2),
      status: "VERIFIED",
    }],
    existing: {
      verificationResultId: "d4000000-0000-4000-8000-000000000001",
      evidenceSetRevision: 1,
      policyVersion: 1,
      status: "VERIFIED",
      reasonCodes: [],
      evidenceIds: [EVIDENCE_ID],
      finalAnswer: 2,
      canonicalDigest: new Uint8Array(32).fill(4),
      completedAt: NOW,
    },
  });
  const repository = new MemoryVerificationRepository(value);
  const service = new VerificationService(
    repository,
    "verification-test-v1",
    () => NOW,
  );

  const result = await service.verify(actor(), REFRESH_ID);
  if (
    result.status !== 200 ||
    result.data.replayed !== true ||
    result.data.result !== "VERIFIED" ||
    repository.persistCalls !== 0
  ) {
    throw new Error("verification replay mutated an accepted result");
  }
});

Deno.test("verification fails closed when policy state type drifts", async () => {
  const value = context({
    proofPolicySnapshot: {
      template_key: "parking.available_spaces.v1",
      state_type: "BINARY",
      fresh_ttl_seconds: 600,
      aging_ratio: 0.7,
      verification_class: "FAST",
      required_witnesses: 1,
      capture: {
        media_required: true,
        location_required: true,
      },
    },
  });
  const repository = new MemoryVerificationRepository(value);
  const service = new VerificationService(
    repository,
    "verification-test-v1",
    () => NOW,
  );

  try {
    await service.verify(actor(), REFRESH_ID);
    throw new Error("policy state-type drift unexpectedly verified");
  } catch (error) {
    if (faultCode(error) !== "VERIFICATION_NOT_ELIGIBLE") throw error;
  }
  if (repository.persistCalls !== 0) {
    throw new Error("verification persisted after policy state-type drift");
  }
});

Deno.test("verification resolves two-of-three numeric majority without averaging", async () => {
  const value = context({
    proofPolicySnapshot: {
      ...context().proofPolicySnapshot,
      verification_class: "CORROBORATED",
      required_witnesses: 2,
      numeric: {
        scale: 0,
        min: 0,
        conflict_tolerance: 1,
        allow_two_of_three: true,
      },
    },
    evidence: [
      evidence(EVIDENCE_ID, 2),
      evidence(EVIDENCE_ID_2, 2, 1_000),
      evidence("d2000000-0000-4000-8000-000000000003", 7, 2_000),
    ],
  });
  const repository = new MemoryVerificationRepository(value);
  const service = new VerificationService(
    repository,
    "verification-test-v1",
    () => NOW,
  );

  const result = await service.verify(actor(), REFRESH_ID);
  if (
    result.data.result !== "VERIFIED" ||
    result.data.final_answer !== 2 ||
    result.data.refresh_status !== "VERIFIED"
  ) {
    throw new Error("two-of-three numeric majority did not resolve deterministically");
  }
});

Deno.test("verification maps malformed frozen policy to a stable fail-closed fault", async () => {
  const value = context({
    proofPolicySnapshot: {
      ...context().proofPolicySnapshot,
      state_type: "BINARY",
    },
  });
  const repository = new MemoryVerificationRepository(value);
  const service = new VerificationService(
    repository,
    "verification-test-v1",
    () => NOW,
  );

  try {
    await service.verify(actor(), REFRESH_ID);
    throw new Error("malformed frozen policy unexpectedly verified");
  } catch (error) {
    if (faultCode(error) !== "VERIFICATION_NOT_ELIGIBLE") throw error;
  }
  if (repository.persistCalls !== 0) {
    throw new Error("verification persisted after malformed frozen policy");
  }
});
