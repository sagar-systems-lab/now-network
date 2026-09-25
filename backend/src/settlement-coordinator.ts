import {
  transitionPayment,
} from "../../packages/domain/src/payment-machine.ts";
import { transitionRefresh } from "../../packages/domain/src/refresh-machine.ts";
import {
  deriveSettlementOperationHashV1,
} from "./settlement-identity.ts";
import type {
  SettlementOperation,
  SettlementRepository,
} from "./settlement-repository.ts";
import {
  SettlementChainError,
  type SettlementChainClient,
  type SettlementInspection,
} from "./solana-settlement-client.ts";

function recipientMask(operation: {
  requiredWitnesses: number;
  maxWitnesses: number;
  beneficiaries: readonly { claimSlot: number }[];
}): number {
  if (operation.beneficiaries.length !== operation.requiredWitnesses) {
    throw new Error("settlement beneficiary count does not match verification policy");
  }
  let mask = 0;
  for (const beneficiary of operation.beneficiaries) {
    if (
      !Number.isSafeInteger(beneficiary.claimSlot) ||
      beneficiary.claimSlot < 0 ||
      beneficiary.claimSlot >= operation.maxWitnesses ||
      beneficiary.claimSlot > 2
    ) {
      throw new Error("settlement beneficiary claim slot is invalid");
    }
    const bit = 1 << beneficiary.claimSlot;
    if ((mask & bit) !== 0) {
      throw new Error("settlement beneficiary claim slots are not unique");
    }
    mask |= bit;
  }
  if (mask < 1 || mask > 0b111) {
    throw new Error("settlement beneficiary mask is invalid");
  }
  return mask;
}

export type SettlementTickSummary = {
  prepared: number;
  submitted: number;
  ambiguous: number;
  pending: number;
  confirmed: number;
  finalized: number;
  safeRetries: number;
  conflicts: number;
  deferred: number;
};

export class SettlementCoordinator {
  constructor(
    private readonly repository: SettlementRepository,
    private readonly chain: SettlementChainClient,
    private readonly now: () => Date = () => new Date(),
    private readonly reconcileDelayMs = 2_000,
  ) {}

  private nextReconcileAt(observedAt: Date): Date {
    return new Date(observedAt.getTime() + this.reconcileDelayMs);
  }

  async prepareOne(): Promise<SettlementOperation | null> {
    const candidate = await this.repository.findEligible();
    if (candidate.kind === "none") return null;
    const eligibility = candidate.eligibility;
    const mask = recipientMask(eligibility);

    transitionRefresh("VERIFIED", "SETTLEMENT_STARTED", {
      settlementEligible: true,
    });
    transitionPayment("NOT_STARTED", "START_SETTLEMENT", {
      verificationVerified: true,
      escrowLocked: eligibility.lockedRewardAtomic > 0n,
      refreshExpired: false,
      claimantsMatch: true,
      verifierPinned: true,
    });

    const settlementId = crypto.randomUUID();
    const operationId = crypto.randomUUID();
    const operationHash = await deriveSettlementOperationHashV1({
      chainRefreshId: eligibility.chainRefreshId,
      executionHash: eligibility.executionHash,
      verificationDigest: eligibility.verificationDigest,
      operationId,
      recipientMask: mask,
    });

    const created = await this.repository.createOperation({
      eligibility,
      settlementId,
      operationId,
      operationHash,
      recipientMask: mask,
      observedAt: this.now(),
    });
    if (created.kind === "not_eligible") return null;
    if (created.kind === "authority_conflict") {
      throw new Error("settlement authority changed during operation creation");
    }
    return created.operation;
  }

  private async submit(
    operation: SettlementOperation,
    summary: SettlementTickSummary,
  ): Promise<void> {
    const observedAt = this.now();
    try {
      const submission = await this.chain.submit(operation);
      if (submission.kind === "ambiguous") {
        transitionPayment("PENDING", "OUTCOME_AMBIGUOUS");
        await this.repository.markAttempt({
          settlementId: operation.settlementId,
          chainSignature: submission.signature,
          recentBlockhash: submission.recentBlockhash,
          lastValidBlockHeight: submission.lastValidBlockHeight,
          ambiguous: true,
          nextReconcileAt: this.nextReconcileAt(observedAt),
          errorCode: submission.errorCode,
          observedAt,
        });
        summary.ambiguous += 1;
        return;
      }

      await this.repository.markAttempt({
        settlementId: operation.settlementId,
        chainSignature: submission.signature,
        recentBlockhash: submission.recentBlockhash,
        lastValidBlockHeight: submission.lastValidBlockHeight,
        ambiguous: false,
        nextReconcileAt: this.nextReconcileAt(observedAt),
        errorCode: null,
        observedAt,
      });
      summary.submitted += 1;
    } catch (error) {
      if (error instanceof SettlementChainError && !error.retryable) {
        await this.repository.markAuthorityConflict({
          settlementId: operation.settlementId,
          errorCode: error.code,
          observedAt,
        });
        summary.conflicts += 1;
        return;
      }
      await this.repository.defer({
        settlementId: operation.settlementId,
        nextReconcileAt: this.nextReconcileAt(observedAt),
        errorCode: error instanceof SettlementChainError
          ? error.code
          : "SETTLEMENT_DEPENDENCY_UNAVAILABLE",
        observedAt,
      });
      summary.deferred += 1;
    }
  }

  private async applyInspection(
    operation: SettlementOperation,
    inspection: SettlementInspection,
    summary: SettlementTickSummary,
  ): Promise<void> {
    switch (inspection.kind) {
      case "pending":
        await this.repository.defer({
          settlementId: operation.settlementId,
          nextReconcileAt: this.nextReconcileAt(this.now()),
          errorCode: "SETTLEMENT_CONFIRMATION_PENDING",
          observedAt: this.now(),
        });
        summary.pending += 1;
        return;

      case "authority_conflict":
        await this.repository.markAuthorityConflict({
          settlementId: operation.settlementId,
          errorCode: inspection.errorCode,
          observedAt: inspection.observedAt,
        });
        summary.conflicts += 1;
        return;

      case "confirmed":
        transitionPayment(
          operation.status === "VERIFYING" ? "VERIFYING" : "PENDING",
          "PAYMENT_CONFIRMED",
          {
            chainConfirmed: true,
            settlementDigestMatches: true,
          },
        );
        await this.repository.markConfirmed({
          settlementId: operation.settlementId,
          chainSignature: inspection.signature,
          commitment: inspection.commitment,
          observedAt: inspection.observedAt,
        });
        if (inspection.commitment === "finalized") summary.finalized += 1;
        else summary.confirmed += 1;
        return;

      case "not_settled":
        if (operation.status === "CONFIRMED") {
          await this.repository.markAuthorityConflict({
            settlementId: operation.settlementId,
            errorCode: "CONFIRMED_SETTLEMENT_DISAPPEARED",
            observedAt: inspection.observedAt,
          });
          summary.conflicts += 1;
          return;
        }
        transitionPayment(
          operation.status === "VERIFYING" ? "VERIFYING" : "PENDING",
          "PROVEN_NOT_SETTLED",
          { settlementDefinitivelyAbsent: true },
        );
        await this.repository.markNotSettled({
          settlementId: operation.settlementId,
          observedAt: inspection.observedAt,
        });
        summary.safeRetries += 1;
        return;
    }
  }

  private async process(
    operation: SettlementOperation,
    summary: SettlementTickSummary,
  ): Promise<void> {
    if (operation.status === "ELIGIBLE") {
      await this.submit(operation, summary);
      return;
    }

    if (operation.status === "NOT_SETTLED") {
      // Re-check the old attempt before reusing the logical operation.
      const inspection = await this.chain.inspect(operation);
      if (inspection.kind !== "not_settled") {
        await this.applyInspection(operation, inspection, summary);
        return;
      }
      transitionPayment("NOT_SETTLED", "RETRY_SETTLEMENT", {
        retryAuthorized: true,
        settlementDefinitivelyAbsent: true,
        sameOperationIdentity: true,
      });
      await this.submit(operation, summary);
      return;
    }

    if (
      operation.status === "SUBMITTED" ||
      operation.status === "VERIFYING" ||
      operation.status === "CONFIRMED"
    ) {
      try {
        const inspection = await this.chain.inspect(operation);
        await this.applyInspection(operation, inspection, summary);
      } catch (error) {
        if (error instanceof SettlementChainError && !error.retryable) {
          await this.repository.markAuthorityConflict({
            settlementId: operation.settlementId,
            errorCode: error.code,
            observedAt: this.now(),
          });
          summary.conflicts += 1;
          return;
        }
        await this.repository.defer({
          settlementId: operation.settlementId,
          nextReconcileAt: this.nextReconcileAt(this.now()),
          errorCode: error instanceof SettlementChainError
            ? error.code
            : "SETTLEMENT_RECONCILIATION_UNAVAILABLE",
          observedAt: this.now(),
        });
        summary.deferred += 1;
      }
    }
  }

  async runOnce(limit = 8): Promise<SettlementTickSummary> {
    const boundedLimit = Math.max(1, Math.min(32, Math.trunc(limit)));
    const summary: SettlementTickSummary = {
      prepared: 0,
      submitted: 0,
      ambiguous: 0,
      pending: 0,
      confirmed: 0,
      finalized: 0,
      safeRetries: 0,
      conflicts: 0,
      deferred: 0,
    };

    for (let index = 0; index < boundedLimit; index += 1) {
      const operation = await this.prepareOne();
      if (operation === null) break;
      summary.prepared += 1;
    }

    const due = await this.repository.listDue({
      observedAt: this.now(),
      limit: boundedLimit,
    });
    for (const operation of due) {
      await this.process(operation, summary);
    }
    return summary;
  }
}
