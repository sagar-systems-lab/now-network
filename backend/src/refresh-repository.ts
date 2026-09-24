import type { VerificationClass } from "../../packages/contracts/src/core.ts";
import type { RefreshStatus } from "../../packages/contracts/src/lifecycle.ts";
import type { RefreshPayoutRule } from "../../packages/contracts/src/refresh-intent.ts";
import type { PolicyTemplateV1 } from "../../packages/policy/src/types.ts";

export type RefreshRecord = {
  refreshId: string;
  stateId: string;
  stateVersion: number;
  requesterActorId: string;
  status: RefreshStatus;
  verificationClass: VerificationClass;
  requiredWitnesses: number;
  maxWitnesses: number;
  proofPolicySnapshot: PolicyTemplateV1;
  proofPolicyDigest: Uint8Array;
  intentCoreHash: Uint8Array;
  refreshExpiresAt: Date;
  evidenceDeadline: Date;
  rewardMint: string;
  creatorWalletAddress: string;
  fundingTargetAtomic: bigint;
  payoutRule: RefreshPayoutRule;
  chainRefreshId: Uint8Array;
  stateIdDigest: Uint8Array;
  fundingOperationId: string | null;
  chainRefreshAddress: string | null;
  chainStatus: string | null;
  chainTotalFunded: bigint;
  chainObservedAt: Date | null;
  createdAt: Date;
  updatedAt: Date;
  revision: number;
};

export type NewRefreshRecord = Omit<
  RefreshRecord,
  | "fundingOperationId"
  | "chainRefreshAddress"
  | "chainStatus"
  | "chainTotalFunded"
  | "chainObservedAt"
  | "createdAt"
  | "updatedAt"
  | "revision"
>;

export type CreateRefreshResult =
  | { kind: "created"; refresh: RefreshRecord }
  | { kind: "replayed"; refresh: RefreshRecord }
  | { kind: "idempotency_conflict" };

export type FundingIntentAddresses = {
  refreshAddress: string;
  contributionAddress: string;
  vaultTokenAccount: string;
  configAddress: string;
};

export type PrepareFundingInput = {
  refreshId: string;
  actorId: string;
  idempotencyKey: string;
  requestHash: Uint8Array;
  idempotencyExpiresAt: Date;
  operationId: string;
  observedAt: Date;
  addresses: FundingIntentAddresses;
};

export type PrepareFundingResult =
  | { kind: "prepared"; refresh: RefreshRecord; operationId: string }
  | { kind: "replayed"; refresh: RefreshRecord; operationId: string }
  | { kind: "idempotency_conflict" }
  | { kind: "not_found" }
  | { kind: "actor_mismatch" }
  | { kind: "not_fundable" }
  | { kind: "expired" };

export type ConfirmFundingInput = {
  refreshId: string;
  actorId: string;
  idempotencyKey: string;
  requestHash: Uint8Array;
  idempotencyExpiresAt: Date;
  chainRefreshAddress: string;
  chainContributionAddress: string;
  chainSignature: string;
  chainCommitment: "confirmed" | "finalized";
  contributionAmountAtomic: bigint;
  chainTotalFundedAtomic: bigint;
  observedAt: Date;
};

export type ConfirmFundingResult =
  | { kind: "confirmed"; refresh: RefreshRecord }
  | { kind: "replayed"; refresh: RefreshRecord }
  | { kind: "idempotency_conflict" }
  | { kind: "not_found" }
  | { kind: "actor_mismatch" }
  | { kind: "not_fundable" }
  | { kind: "expired" };

export interface RefreshRepository {
  createOrReplay(input: {
    idempotencyKey: string;
    requestHash: Uint8Array;
    idempotencyExpiresAt: Date;
    refresh: NewRefreshRecord;
  }): Promise<CreateRefreshResult>;

  getRefresh(refreshId: string): Promise<RefreshRecord | null>;

  prepareFunding(input: PrepareFundingInput): Promise<PrepareFundingResult>;

  confirmFunding(input: ConfirmFundingInput): Promise<ConfirmFundingResult>;
}
