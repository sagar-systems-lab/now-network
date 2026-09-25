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
  PreparedSettlementAttempt,
  SettlementBroadcast,
  SettlementChainClient,
  SettlementInspection,
} from "../src/settlement-chain.ts";

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
  const hasAttempt = !["ELIGIBLE", "NOT_SETTLED"].includes(status);
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
    chainSignature: hasAttempt ? "sig-old" : null,
    recentBlockhash: hasAttempt ? "blockhash-old" : null,
    lastValidBlockHeight: hasAttempt ? 100 : null,
    signedTransactionBase64: hasAttempt ? "c2lnbmVkLXR4LW9sZA==" : null,
    chainCommitment: null,
    attemptCount: hasAttempt ? 1 : 0,
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

function prepared(
  suffix: string,
  lastValidBlockHeight = 200,
): PreparedSettlementAttempt {
  return {
    signature: `sig-${suffix}`,
    recentBlockhash: `blockhash-${suffix}`,
    lastValidBlockHeight,
    signedTransactionBase64: btoa(`signed-${suffix}`),
  };
}

class MemorySettlementRepository implements SettlementRepository {
  candidate: SettlementEligibility | null = null;
  current: SettlementOperation | null = null;
  conflictCodes: string[] = [];
  deferCodes: string[] = [];
  reservations = 0;
  broadcasts = 0;

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
      recipientWallets: input.eligibility.beneficiaries.map((item) => item.walletAddress),
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

  reserveAttempt(
    input: Parameters<SettlementRepository["reserveAttempt"]>[0],
  ): Promise<SettlementMutationResult> {
    if (this.current === null) return Promise.resolve({ kind: "not_found" });
    if (this.current.status === "SUBMITTING") {
      return Promise.resolve({ kind: "replayed", operation: this.current });
    }
    this.reservations += 1;
    this.current = {
      ...this.current,
      status: "SUBMITTING",
      chainSignature: input.chainSignature,
      recentBlockhash: input.recentBlockhash,
      lastValidBlockHeight: input.lastValidBlockHeight,
      signedTransactionBase64: input.signedTransactionBase64,
      attemptCount: this.current.attemptCount + 1,
      nextReconcileAt: input.observedAt,
      updatedAt: input.observedAt,
    };
    return Promise.resolve({ kind: "updated", operation: this.current });
  }

  markBroadcast(
    input: Parameters<SettlementRepository["markBroadcast"]>[0],
  ): Promise<SettlementMutationResult> {
    if (this.current === null) return Promise.resolve({ kind: "not_found" });
    if (this.current.chainSignature !== input.chainSignature) {
      return Promise.resolve({ kind: "authority_conflict" });
    }
    this.broadcasts += 1;
    this.current = {
      ...this.current,
      status: input.ambiguous ? "VERIFYING" : "SUBMITTED",
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
  prepared: PreparedSettlementAttempt[] = [];
  broadcasts: SettlementBroadcast[] = [];
  inspections: SettlementInspection[] = [];
  preparedOperations: SettlementOperation[] = [];
  broadcastAttempts: PreparedSettlementAttempt[] = [];
  inspectedOperations: SettlementOperation[] = [];

  prepare(operation: SettlementOperation): Promise<PreparedSettlementAttempt> {
    this.preparedOperations.push(structuredClone(operation));
    const next = this.prepared.shift();
    if (!next) throw new Error("unexpected settlement preparation");
    return Promise.resolve(next);
  }

  broadcast(attempt: PreparedSettlementAttempt): Promise<SettlementBroadcast> {
    this.broadcastAttempts.push(structuredClone(attempt));
    const next = this.broadcasts.shift();
    if (!next) throw new Error("unexpected settlement broadcast");
    return Promise.resolve(next);
  }

  inspect(operation: SettlementOperation): Promise<SettlementInspection> {
    this.inspectedOperations.push(structuredClone(operation));
    const next = this.inspections.shift();
    if (!next) throw new Error("unexpected settlement inspection");
    return Promise.resolve(next);
  }
}

Deno.test("settlement freezes verified two-of-three recipients before broadcast", async () => {
  const repository = new MemorySettlementRepository();
  repository.candidate = eligibility();
  const chain = new FakeSettlementChain();
  chain.prepared.push(prepared("new"));
  chain.broadcasts.push({ kind: "accepted" });
  const coordinator = new SettlementCoordinator(
    repository,
    chain,
    () => NOW,
    2_000,
  );

  const summary = await coordinator.runOnce(8);
  if (
    summary.prepared !== 1 ||
    summary.signed !== 1 ||
    summary.submitted !== 1 ||
    repository.current?.recipientMask !== 0b101 ||
    repository.reservations !== 1 ||
    repository.broadcasts !== 1
  ) {
    throw new Error("verified beneficiary settlement was not durably submitted");
  }
});

Deno.test("ambiguous broadcast enters reconciliation without a new transaction", async () => {
  const repository = new MemorySettlementRepository();
  repository.current = operation("ELIGIBLE");
  const chain = new FakeSettlementChain();
  chain.prepared.push(prepared("ambiguous"));
  chain.broadcasts.push({
    kind: "ambiguous",
    errorCode: "SETTLEMENT_SUBMISSION_AMBIGUOUS",
  });
  const coordinator = new SettlementCoordinator(repository, chain, () => NOW);

  const summary = await coordinator.runOnce(1);
  if (
    summary.signed !== 1 ||
    summary.ambiguous !== 1 ||
    repository.current.status !== "VERIFYING" ||
    chain.preparedOperations.length !== 1 ||
    chain.broadcastAttempts.length !== 1 ||
    chain.inspectedOperations.length !== 0
  ) {
    throw new Error("ambiguous settlement did not enter reconciliation");
  }
});

Deno.test("reserved transaction resumes after a worker crash", async () => {
  const repository = new MemorySettlementRepository();
  repository.current = operation("SUBMITTING");
  const chain = new FakeSettlementChain();
  chain.broadcasts.push({ kind: "accepted" });
  const coordinator = new SettlementCoordinator(repository, chain, () => NOW);

  const summary = await coordinator.runOnce(1);
  if (
    summary.signed !== 0 ||
    summary.submitted !== 1 ||
    chain.preparedOperations.length !== 0 ||
    chain.broadcastAttempts[0]?.signature !== "sig-old"
  ) {
    throw new Error("reserved transaction was not resumed exactly");
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
    chain.preparedOperations.length !== 0 ||
    chain.broadcastAttempts.length !== 0 ||
    repository.deferCodes.at(-1) !== "SETTLEMENT_CONFIRMATION_PENDING"
  ) {
    throw new Error("pending settlement was resubmitted");
  }
});

Deno.test("proven absent settlement retries the same logical operation", async () => {
  const repository = new MemorySettlementRepository();
  repository.current = operation("NOT_SETTLED", {
    chainSignature: "sig-old",
    recentBlockhash: "blockhash-old",
    lastValidBlockHeight: 100,
    signedTransactionBase64: "c2lnbmVkLXR4LW9sZA==",
    attemptCount: 1,
  });
  const originalId = repository.current.operationId;
  const originalHash = [...repository.current.operationHash];
  const chain = new FakeSettlementChain();
  chain.inspections.push({ kind: "not_settled", observedAt: NOW });
  chain.prepared.push(prepared("retry", 300));
  chain.broadcasts.push({ kind: "accepted" });
  const coordinator = new SettlementCoordinator(repository, chain, () => NOW);

  const summary = await coordinator.runOnce(1);
  const retried = chain.preparedOperations[0];
  if (
    summary.signed !== 1 ||
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
    chainSignature: "sig-old",
    recentBlockhash: "blockhash-old",
    lastValidBlockHeight: 100,
    signedTransactionBase64: "c2lnbmVkLXR4LW9sZA==",
    refreshExpiresAt: new Date("2026-09-25T11:59:59.000Z"),
  });
  const chain = new FakeSettlementChain();
  const coordinator = new SettlementCoordinator(repository, chain, () => NOW);

  const summary = await coordinator.runOnce(1);
  if (
    summary.conflicts !== 1 ||
    chain.preparedOperations.length !== 0 ||
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
