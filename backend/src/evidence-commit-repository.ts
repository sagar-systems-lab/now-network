import type { EvidenceVideo } from "./evidence-video.ts";
import type { RefreshStatus, StateType } from "../../packages/contracts/src/lifecycle.ts";
import type { ClaimStatus } from "../../packages/domain/src/claim-machine.ts";
import type { PolicyTemplateV1 } from "../../packages/policy/src/types.ts";

export type EvidenceLocationSample = {
  sampleOrder: number;
  lat: number;
  lng: number;
  accuracyM: number | null;
  provider: string | null;
  mockSignal: boolean | null;
  capturedOffsetMs: number | null;
};

export type EvidenceCommitContext = {
  evidenceId: string;
  challengeId: string;
  refreshId: string;
  acceptanceId: string;
  actorId: string;
  walletAddress: string;
  nonceHash: Uint8Array;
  challengeStatus: "ISSUED" | "CONSUMED" | "EXPIRED" | "REVOKED";
  challengeIssuedAt: Date;
  challengeExpiresAt: Date;
  reservedObjectKey: string | null;
  reservedMediaMime: string | null;
  claimStatus: ClaimStatus;
  claimDeadline: Date | null;
  claimRevision: number;
  refreshStatus: RefreshStatus;
  requiredWitnesses: number;
  refreshExpiresAt: Date;
  evidenceDeadline: Date;
  refreshRevision: number;
  stateId: string;
  stateVersion: number;
  stateType: StateType;
  intentCoreHash: Uint8Array;
  executionHash: Uint8Array | null;
  chainLockedRewardAtomic: bigint | null;
  chainRefreshAddress: string | null;
  proofPolicySnapshot: PolicyTemplateV1;
};

export type EvidenceCommitRecord = {
  evidenceId: string;
  refreshId: string;
  acceptanceId: string;
  challengeId: string;
  actorId: string;
  status: "COMMITTED";
  mediaObjectKey: string;
  mediaSha256: Uint8Array;
  mediaSizeBytes: number;
  mediaMime: string;
  committedAt: Date;
  revision: number;
  claimRevision: number;
  refreshRevision: number;
  refreshStatus: RefreshStatus;
};

export type CommitEvidenceResult =
  | ({ kind: "committed" } & EvidenceCommitRecord)
  | ({ kind: "replayed" } & EvidenceCommitRecord)
  | { kind: "not_found" }
  | { kind: "actor_mismatch" }
  | { kind: "idempotency_conflict" }
  | { kind: "challenge_invalid" }
  | { kind: "expired" }
  | { kind: "claim_not_active" }
  | { kind: "reservation_mismatch" }
  | { kind: "execution_conflict" }
  | { kind: "exact_replay" }
  | { kind: "state_conflict" };

export interface EvidenceCommitRepository {
  getContext(evidenceId: string): Promise<EvidenceCommitContext | null>;

  commitEvidence(input: {
    evidenceId: string;
    actorId: string;
    idempotencyKey: string;
    requestHash: Uint8Array;
    idempotencyExpiresAt: Date;
    nonceHash: Uint8Array;
    executionHash: Uint8Array;
    answerType: StateType;
    answerValue: unknown;
    captureStartedMonotonicMs: number;
    captureCompletedMonotonicMs: number;
    mediaObjectKey: string;
    mediaSha256: Uint8Array;
    mediaSizeBytes: number;
    mediaMime: string;
    video?: EvidenceVideo | null;
    locationSamples: readonly EvidenceLocationSample[];
    observedAt: Date;
  }): Promise<CommitEvidenceResult>;
}
