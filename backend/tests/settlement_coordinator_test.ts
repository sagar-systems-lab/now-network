import type {
  CreateSettlementResult,
  PrepareSettlementResult,
  SettlementEligibility,
  SettlementMutationResult,
  SettlementOperation,
  SettlementRepository,
} from "../src/settlement-repository.ts";
import { SettlementCoordinator } from "../src/settlement-coordinator.ts";
import type {
  SettlementChainClient,
  SettlementInspection,
  SettlementSubmission,
} from "../src/solana-settlement-client.ts";

const NOW = new Date("2026-09-25T12:00:00.000Z");

function eligibility(
  overrides: Partial<SettlementEligibility> = {},
): SettlementEligibility {
  return {
    refreshId: "f1000000-0000-4000-8000-000000000001",
    refreshStatus: "VERIFIED",
    verificationResultId: "f2000000-0000-4000-8000-000000000001",
    verificationDigest: new Uint8Array(32).fill(3),
    executionHash: new Uint8Array(32).fill(2),
    chainRefreshId: new Uint8Array(32).fill(1),
    chainRefreshAddress: "11111111111111111111111111111111",
    refreshExpiresAt: new Date("2026-09-25T12:10:00.000Z"),
    rewardMint: "So11111111111111111111111111111111111111112",
    lockedRewardAtomic: 500_000n,
    requiredWitnesses: 2,
    maxWitnesses: 3,
    beneficiaries: [
      {
        evidenceId: "f3000000-0000-4000-8000-000000000001",
        acceptanceId: "f4000000-0000-4000-8000-000000000001",
        actorId: "f5000000-0000-4000-8000-000000000001",
        walletAddress: "11111111111111111111111111111111",
        claimSlot: 0,
      },
      {
        evidenceId: "f3000000-0000-4000-8000-000000000002",
        acceptanceId: "f4000000-0000-4000-8000-000000000002",
        actorId: "f5000000-0000-4000-8000-000000000002",
        walletAddress: "So11111111111111111111111111111111111111112",
        claimSlot: 2,
      },
    ],
    ...overrides,
  };
}

function operation(
  status: SettlementOperation["status"],
  overrides: Partial<SettlementOperation> = {},
): SettlementOperation {
  return {
    settlementId: "f6000000-0000-4000-8000-000000000001",
    refreshId: "f1000000-0000-4000-8000-000000000001",
    verificationResultId: "f2000000-0000-4000-8000-000000000001",
    operationId: "550e8400-e29b-41d4-a716-446655440000",
    operationHash: new Uint8Array(32).fill(9),
    verificationDigest: new Uint8Array(32).fill(3),
    executionHash: new Uint8Array(32).fill(2),
    recipientMask: 0b101,
    recipientWallets: [
      "11111111111111111111111111111111",
      "So11111111111111111111111111111111111111112",
    ],
    chainRefreshId: new Uint8Array(32).fill(1),
    rewardMint: "So11111111111111111111111111111111111111112",
    lockedRewardAtomic: 500_000n,
    chainRefreshAddress: "11111111111111111111111111111111",
    refreshExpiresAt: new Date("2026-09-25T12:10:00.000Z"),
    status,
    chainSignature: status === "ELIGIBLE" ? null : "sig-old",
    recentBlockhash: status === "ELIGIBLE" ? null : "blockhash-old",
    lastValidBlockHeight: status === "ELIGIBLE" ? null : 100,
    chainCommitment: null,
    attemptCount: status === "ELIGIBLE" ? 0 : 1,
    nextReconcileAt: NOW,
    lastChainObservedAt: null,
    lastErrorCode: null,
    createdAt: NOW,
    updatedAt: NOW,
    confirmedAt: null,
    finalizedAt: null,
    ...overrides,
  };
}

class MemorySettlementRepository implements SettlementRepository {
  candidate: SettlementEligibility | null = null;
  current: SettlementOperation | null = null;
  conflictCodes: string[] = [];
  deferCodes: string[] = [];
  markAttempts = 0;

  findEligible(): Promise<PrepareSettlementResult> {
    return Promise.resolve(
      this.candidate === null
        ? { kind: "none" }
        : { kind: "eligible", eligibility: structuredClone(this.candidate) },
    );
  }

  createOperation(
    input: Parameters<SettlementRepository["createOperation"]>[0],
  ): Promise<CreateSettlementResult> {
    if (this.current !== null) {
      return Promise.resolve({ kind: "replayed", operation: this.current });
    }
    this.candidate = null;
    this.current = operation("ELIGIBLE", {
      settlementId: input.settlementId,
      refreshId: input.eligibility.refreshId,
      verificationResultId: input.eligibility.verificationResultId,
      operationId: input.operationId,
      operationHash: input.operationHash,
      verificationDigest: input.eligibility.verificationDigest,
      executionHash: input.eligibility.executionHash,
      recipientMask: input.recipientMask,
      recipientWallets: input.eligibility.beneficiaries.map((item) =>
        item.walletAddress
      ),
      chainRefreshId: input.eligibility.chainRefreshId,
      rewardMint: input.eligibility.rewardMint,
      lockedRewardAtomic: input.eligibility.lockedRewardAtomic,
      chainRefreshAddress: input.eligibility.chainRefreshAddress,
      refreshExpiresAt: input.eligibility.refreshExpiresAt,
      nextReconcileAt: input.observedAt,
      createdAt: input.observedAt,
      updatedAt: input.observedAt,
    });
    return Promise.resolve({ kind: "created", operation: this.current });
  }

  listDue(): Promise<SettlementOperation[]> {
    return Promise.resolve(
      this.current === null ? [] : [structuredClone(this.current)],
    );
  }

  markAttempt(
    input: Parameters<SettlementRepository["markAttempt"]>[0],
  ): Promise<SettlementMutationResult> {
    if (this.current === null) return Promise.resolve({ kind: "not_found" });
    this.markAttempts += 1;
    this.current = {
      ...this.current,
      status: input.ambiguous ? "VERIFYING" : "SUBMITTED",
      chainSignature: input.chainSignature,
      recentBlockhash: input.recentBlockhash,
      lastValidBlockHeight: input.lastValidBlockHeight,
      attemptCount: this.current.attemptCount + 1,
      nextReconcileAt: input.nextReconcileAt,
      lastErrorCode: input.errorCode,
      updatedAt: input.observedAt,
    };
    return Promise.resolve({ kind: "updated", operation: this.current });
  }

  markConfirmed(
    input: Parameters<SettlementRepository["markConfirmed"]>[0],
  ): Promise<SettlementMutationResult> {
    if (this.current === null) return Promise.resolve({ kind: "not_found" });
    this.current = {
      ...this.current,
      status: input.commitment === "finalized" ? "FINALIZED" : "CONFIRMED",
      chainSignature: input.chainSignature,
      chainCommitment: input.commitment,
      confirmedAt: input.observedAt,
      finalizedAt: input.commitment === "finalized" ? input.observedAt : null,
      updatedAt: input.observedAt,
    };
    return Promise.resolve({ kind: "updated", operation: this.current });
  }

  markNotSettled(
    input: Parameters<SettlementRepository["markNotSettled"]>[0],
  ): Promise<SettlementMutationResult> {
    if (this.current === null) return Promise.resolve({ kind: "not_found" });
    this.current = {
      ...this.current,
      status: "NOT_SETTLED",
      nextReconcileAt: input.observedAt,
      updatedAt: input.observedAt,
    };
    return Promise.resolve({ kind: "updated", operation: this.current });
  }

  defer(
    input: Parameters<SettlementRepository["defer"]>[0],
  ): Promise<SettlementMutationResult> {
    if (this.current === null) return Promise.resolve({ kind: "not_found" });
    this.deferCodes.push(input.errorCode);
    this.current = {
      ...this.current,
      nextReconcileAt: input.nextReconcileAt,
      lastErrorCode: input.errorCode,
      updatedAt: input.observedAt,
    };
    return Promise.resolve({ kind: "updated", operation: this.current });
  }

  markAuthorityConflict(
    input: Parameters<SettlementRepository["markAuthorityConflict"]>[0],
  ): Promise<SettlementMutationResult> {
    if (this.current === null) return Promise.resolve({ kind: "not_found" });
    this.conflictCodes.push(input.errorCode);
    this.current = {
      ...this.current,
      status: "FAILED",
      lastErrorCode: input.errorCode,
      updatedAt: input.observedAt,
    };
    return Promise.resolve({ kind: "updated", operation: this.current });
  }
}

class FakeSettlementChain implements SettlementChainClient {
  submissions: SettlementSubmission[] = [];
  inspections: SettlementInspection[] = [];
  submittedOperations: SettlementOperation[] = [];
  inspectedOperations: SettlementOperation[] = [];

  submit(operation: SettlementOperation): Promise<SettlementSubmission> {
    this.submittedOperations.push(structuredClone(operation));
    const next = this.submissions.shift();
    if (!next) throw new Error("unexpected settlement submission");
    return Promise.resolve(next);
  }

  inspect(operation: SettlementOperation): Promise<SettlementInspection> {
    this.inspectedOperations.push(structuredClone(operation));
    const next = this.inspections.shift();
    if (!next) throw new Error("unexpected settlement inspection");
    return Promise.resolve(next);
  }
}

Deno.test("settlement prepares verified two-of-three recipients and submits once", async () => {
  const repository = new MemorySettlementRepository();
  repository.candidate = eligibility();
  const chain = new FakeSettlementChain();
  chain.submissions.push({
    kind: "submitted",
    signature: "sig-new",
    recentBlockhash: "blockhash-new",
    lastValidBlockHeight: 200,
  });
  const coordinator = new SettlementCoordinator(
    repository,
    chain,
    () => NOW,
    2_000,
  );

  const summary = await coordinator.runOnce(8);
  if (
    summary.prepared !== 1 ||
    summary.submitted !== 1 ||
    repository.current?.recipientMask !== 0b101 ||
    repository.markAttempts !== 1 ||
    chain.submittedOperations.length !== 1
  ) {
    throw new Error("verified beneficiary settlement was not submitted exactly once");
  }
});

Deno.test("ambiguous submission enters reconciliation without blind retry", async () => {
  const repository = new MemorySettlementRepository();
  repository.current = operation("ELIGIBLE");
  const chain = new FakeSettlementChain();
  chain.submissions.push({
    kind: "ambiguous",
    signature: "sig-ambiguous",
    recentBlockhash: "blockhash-a",
    lastValidBlockHeight: 200,
    errorCode: "SETTLEMENT_SUBMISSION_AMBIGUOUS",
  });
  const coordinator = new SettlementCoordinator(repository, chain, () => NOW);

  const summary = await coordinator.runOnce(1);
  if (
    summary.ambiguous !== 1 ||
    repository.current.status !== "VERIFYING" ||
    chain.submittedOperations.length !== 1 ||
    chain.inspectedOperations.length !== 0
  ) {
    throw new Error("ambiguous settlement was not isolated for reconciliation");
  }
});

Deno.test("pending settlement is deferred without resubmission", async () => {
  const repository = new MemorySettlementRepository();
  repository.current = operation("VERIFYING");
  const chain = new FakeSettlementChain();
  chain.inspections.push({ kind: "pending" });
  const coordinator = new SettlementCoordinator(repository, chain, () => NOW);

  const summary = await coordinator.runOnce(1);
  if (
    summary.pending !== 1 ||
    chain.submittedOperations.length !== 0 ||
    repository.deferCodes.at(-1) !== "SETTLEMENT_CONFIRMATION_PENDING"
  ) {
    throw new Error("pending settlement was resubmitted");
  }
});

Deno.test("proven absent settlement retries with the same logical operation", async () => {
  const repository = new MemorySettlementRepository();
  repository.current = operation("NOT_SETTLED");
  const originalId = repository.current.operationId;
  const originalHash = [...repository.current.operationHash];
  const chain = new FakeSettlementChain();
  chain.inspections.push({ kind: "not_settled", observedAt: NOW });
  chain.submissions.push({
    kind: "submitted",
    signature: "sig-retry",
    recentBlockhash: "blockhash-retry",
    lastValidBlockHeight: 300,
  });
  const coordinator = new SettlementCoordinator(repository, chain, () => NOW);

  const summary = await coordinator.runOnce(1);
  const retried = chain.submittedOperations[0];
  if (
    summary.submitted !== 1 ||
    retried?.operationId !== originalId ||
    JSON.stringify([...retried.operationHash]) !== JSON.stringify(originalHash)
  ) {
    throw new Error("safe settlement retry changed logical operation identity");
  }
});

Deno.test("expired proven-absent settlement cannot retry", async () => {
  const repository = new MemorySettlementRepository();
  repository.current = operation("NOT_SETTLED", {
    refreshExpiresAt: new Date("2026-09-25T11:59:59.000Z"),
  });
  const chain = new FakeSettlementChain();
  const coordinator = new SettlementCoordinator(repository, chain, () => NOW);

  const summary = await coordinator.runOnce(1);
  if (
    summary.conflicts !== 1 ||
    chain.submittedOperations.length !== 0 ||
    chain.inspectedOperations.length !== 0 ||
    repository.conflictCodes.at(-1) !== "SETTLEMENT_WINDOW_EXPIRED"
  ) {
    throw new Error("expired settlement retry was not stopped");
  }
});

Deno.test("matching confirmed settlement resolves the operation", async () => {
  const repository = new MemorySettlementRepository();
  repository.current = operation("VERIFYING");
  const chain = new FakeSettlementChain();
  chain.inspections.push({
    kind: "confirmed",
    commitment: "confirmed",
    signature: "sig-old",
    observedAt: NOW,
  });
  const coordinator = new SettlementCoordinator(repository, chain, () => NOW);

  const summary = await coordinator.runOnce(1);
  if (
    summary.confirmed !== 1 ||
    repository.current.status !== "CONFIRMED" ||
    repository.current.chainCommitment !== "confirmed"
  ) {
    throw new Error("confirmed settlement did not resolve deterministically");
  }
});

Deno.test("chain authority mismatch fails closed", async () => {
  const repository = new MemorySettlementRepository();
  repository.current = operation("VERIFYING");
  const chain = new FakeSettlementChain();
  chain.inspections.push({
    kind: "authority_conflict",
    errorCode: "SETTLEMENT_CHAIN_RESULT_MISMATCH",
    observedAt: NOW,
  });
  const coordinator = new SettlementCoordinator(repository, chain, () => NOW);

  const summary = await coordinator.runOnce(1);
  if (
    summary.conflicts !== 1 ||
    repository.current.status !== "FAILED" ||
    repository.conflictCodes.at(-1) !== "SETTLEMENT_CHAIN_RESULT_MISMATCH"
  ) {
    throw new Error("settlement authority conflict did not fail closed");
  }
});
