import type { RefreshStatus } from "../../packages/contracts/src/lifecycle.ts";
import type { ClaimStatus } from "../../packages/domain/src/claim-machine.ts";

export type ClaimRecord = {
  acceptanceId: string;
  refreshId: string;
  actorId: string;
  walletAddress: string;
  claimSlot: number | null;
  claimDurationSeconds: number | null;
  claimDeadline: Date | null;
  chainSignature: string | null;
  chainStatus: string | null;
  status: ClaimStatus;
  acceptedAt: Date;
  releasedAt: Date | null;
  revision: number;
};

export type ClaimRefreshContext = {
  refreshId: string;
  requesterActorId: string;
  status: RefreshStatus;
  requiredWitnesses: number;
  maxWitnesses: number;
  refreshExpiresAt: Date;
  evidenceDeadline: Date;
  rewardMint: string;
  creatorWalletAddress: string;
  chainRefreshId: Uint8Array;
  stateIdDigest: Uint8Array;
  intentCoreHash: Uint8Array;
  chainRefreshAddress: string | null;
  chainTotalFunded: bigint;
  chainLockedReward: bigint | null;
  revision: number;
};

export type ClaimWithRefresh = {
  claim: ClaimRecord;
  refresh: ClaimRefreshContext;
};

export type ClaimReplayLookup =
  | { kind: "none" }
  | ({ kind: "replayed" } & ClaimWithRefresh)
  | { kind: "idempotency_conflict" };

export type PrepareClaimInput = {
  refreshId: string;
  actorId: string;
  walletAddress: string;
  claimDurationSeconds: number;
  acceptanceId: string;
  idempotencyKey: string;
  requestHash: Uint8Array;
  idempotencyExpiresAt: Date;
  observedAt: Date;
};

export type PrepareClaimResult =
  | ({ kind: "prepared"; created: boolean } & ClaimWithRefresh)
  | ({ kind: "replayed" } & ClaimWithRefresh)
  | { kind: "idempotency_conflict" }
  | { kind: "not_found" }
  | { kind: "self_claim" }
  | { kind: "expired" }
  | { kind: "not_claimable" }
  | { kind: "capacity_full" };

export type ClaimObservationResult =
  | ({ kind: "updated" | "replayed" } & ClaimWithRefresh)
  | { kind: "not_found" }
  | { kind: "actor_mismatch" }
  | { kind: "signature_conflict" }
  | { kind: "authority_conflict" };

export interface ClaimRepository {
  lookupPrepareReplay(input: {
    actorId: string;
    idempotencyKey: string;
    requestHash: Uint8Array;
  }): Promise<ClaimReplayLookup>;

  prepareClaim(input: PrepareClaimInput): Promise<PrepareClaimResult>;

  getClaim(acceptanceId: string): Promise<ClaimWithRefresh | null>;

  markClaimPending(input: {
    acceptanceId: string;
    actorId: string;
    chainSignature: string;
    chainStatus: "CONFIRMING" | "UNKNOWN";
    observedAt: Date;
  }): Promise<ClaimObservationResult>;

  resetClaimAbsent(input: {
    acceptanceId: string;
    actorId: string;
    chainSignature: string;
    observedAt: Date;
  }): Promise<ClaimObservationResult>;

  confirmClaim(input: {
    acceptanceId: string;
    actorId: string;
    walletAddress: string;
    chainSignature: string;
    chainCommitment: "confirmed" | "finalized";
    claimSlot: number;
    claimedAt: Date;
    claimDeadline: Date;
    totalFundedAtomic: bigint;
    lockedRewardAtomic: bigint;
    observedAt: Date;
  }): Promise<ClaimObservationResult>;
}
