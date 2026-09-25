import type {
  RefreshStatus,
  SettlementStatus,
} from "../../packages/contracts/src/lifecycle.ts";

export type SettlementBeneficiary = {
  evidenceId: string;
  acceptanceId: string;
  actorId: string;
  walletAddress: string;
  claimSlot: number;
};

export type SettlementEligibility = {
  refreshId: string;
  refreshStatus: RefreshStatus;
  verificationResultId: string;
  verificationDigest: Uint8Array;
  executionHash: Uint8Array;
  chainRefreshId: Uint8Array;
  chainRefreshAddress: string;
  rewardMint: string;
  lockedRewardAtomic: bigint;
  requiredWitnesses: number;
  maxWitnesses: number;
  beneficiaries: SettlementBeneficiary[];
};

export type SettlementOperation = {
  settlementId: string;
  refreshId: string;
  verificationResultId: string;
  operationId: string;
  operationHash: Uint8Array;
  verificationDigest: Uint8Array;
  executionHash: Uint8Array;
  recipientMask: number;
  rewardMint: string;
  lockedRewardAtomic: bigint;
  chainRefreshAddress: string;
  status: SettlementStatus;
  chainSignature: string | null;
  recentBlockhash: string | null;
  lastValidBlockHeight: number | null;
  chainCommitment: string | null;
  attemptCount: number;
  nextReconcileAt: Date | null;
  lastChainObservedAt: Date | null;
  lastErrorCode: string | null;
  createdAt: Date;
  updatedAt: Date;
  confirmedAt: Date | null;
  finalizedAt: Date | null;
  beneficiaries: SettlementBeneficiary[];
};

export type PrepareSettlementResult =
  | { kind: "eligible"; eligibility: SettlementEligibility }
  | { kind: "none" };

export type CreateSettlementResult =
  | { kind: "created" | "replayed"; operation: SettlementOperation }
  | { kind: "not_eligible" }
  | { kind: "authority_conflict" };

export type SettlementMutationResult =
  | { kind: "updated" | "replayed"; operation: SettlementOperation }
  | { kind: "not_found" }
  | { kind: "authority_conflict" };

export interface SettlementRepository {
  findEligible(): Promise<PrepareSettlementResult>;

  createOperation(input: {
    eligibility: SettlementEligibility;
    settlementId: string;
    operationId: string;
    operationHash: Uint8Array;
    recipientMask: number;
    observedAt: Date;
  }): Promise<CreateSettlementResult>;

  listDue(input: {
    observedAt: Date;
    limit: number;
  }): Promise<SettlementOperation[]>;

  markAttempt(input: {
    settlementId: string;
    chainSignature: string;
    recentBlockhash: string;
    lastValidBlockHeight: number;
    ambiguous: boolean;
    nextReconcileAt: Date;
    errorCode: string | null;
    observedAt: Date;
  }): Promise<SettlementMutationResult>;

  markConfirmed(input: {
    settlementId: string;
    chainSignature: string;
    commitment: "confirmed" | "finalized";
    observedAt: Date;
  }): Promise<SettlementMutationResult>;

  markNotSettled(input: {
    settlementId: string;
    observedAt: Date;
  }): Promise<SettlementMutationResult>;

  markAuthorityConflict(input: {
    settlementId: string;
    errorCode: string;
    observedAt: Date;
  }): Promise<SettlementMutationResult>;
}
