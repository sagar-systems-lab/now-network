import type { EvidenceVideo } from "./evidence-video.ts";
import type { VerificationResult } from "../../packages/contracts/src/core.ts";
import type {
  EvidenceStatus,
  RefreshStatus,
  StateType,
  VerificationStatus,
} from "../../packages/contracts/src/lifecycle.ts";
import type { VerificationReasonCode } from "../../packages/contracts/src/reason-codes.ts";
import type { PolicyTemplateV1 } from "../../packages/policy/src/types.ts";

export type VerificationEvidence = {
  evidenceId: string;
  actorId: string;
  answerType: StateType;
  answerValue: unknown;
  intentCoreHash: Uint8Array;
  executionHash: Uint8Array;
  mediaSha256: Uint8Array | null;
  mediaSizeBytes: number | null;
  mediaMime: string | null;
  video?: EvidenceVideo | null;
  locationSampleCount: number;
  hasMockLocation: boolean;
  serverObservationEarliest: Date;
  serverObservationLatest: Date;
  committedAt: Date;
  status: EvidenceStatus;
};

export type ExistingVerification = {
  verificationResultId: string;
  evidenceSetRevision: number;
  policyVersion: number;
  status: VerificationStatus;
  reasonCodes: string[];
  evidenceIds: string[];
  finalAnswer: unknown | null;
  canonicalDigest: Uint8Array;
  completedAt: Date | null;
};

export type VerificationContext = {
  refreshId: string;
  requesterActorId: string;
  refreshStatus: RefreshStatus;
  refreshRevision: number;
  refreshExpiresAt: Date;
  evidenceDeadline: Date;
  stateId: string;
  stateVersion: number;
  stateType: StateType;
  answerSchema: unknown;
  intentCoreHash: Uint8Array;
  executionHash: Uint8Array | null;
  proofPolicySnapshot: PolicyTemplateV1;
  evidence: VerificationEvidence[];
  existing: ExistingVerification | null;
};

export type VerificationOutcome = {
  result: VerificationResult;
  status: "VERIFIED" | "CONFLICT" | "WAITING_FOR_MORE_EVIDENCE" | "REJECTED";
  reasonCodes: readonly VerificationReasonCode[];
  policyReasonCodes: readonly string[];
  finalAnswer: unknown | null;
  matchingEvidenceIds: readonly string[];
  verificationTrace: Record<string, unknown>;
  locationSummary: Record<string, unknown>;
  freshnessSummary: Record<string, unknown>;
  mediaIntegritySummary: Record<string, unknown>;
  replaySummary: Record<string, unknown>;
  conflictSummary: Record<string, unknown> | null;
};

type PersistedVerificationRecord = {
  verificationResultId: string;
  refreshId: string;
  evidenceSetRevision: number;
  policyVersion: number;
  result: VerificationResult;
  status: VerificationStatus;
  reasonCodes: string[];
  evidenceIds: string[];
  finalAnswer: unknown | null;
  canonicalDigest: Uint8Array;
  refreshStatus: RefreshStatus;
  refreshRevision: number;
  completedAt: Date;
};

export type PersistVerificationResult =
  | ({ kind: "recorded" } & PersistedVerificationRecord)
  | ({ kind: "replayed" } & PersistedVerificationRecord)
  | { kind: "not_found" }
  | { kind: "actor_mismatch" }
  | { kind: "not_eligible" }
  | { kind: "expired" }
  | { kind: "evidence_set_changed" }
  | { kind: "policy_changed" }
  | { kind: "execution_conflict" };

export interface VerificationRepository {
  getContext(refreshId: string): Promise<VerificationContext | null>;

  persist(input: {
    actorId: string;
    refreshId: string;
    verificationResultId: string;
    evidenceSetRevision: number;
    policyVersion: number;
    policySnapshot: PolicyTemplateV1;
    evidenceIds: readonly string[];
    executionHash: Uint8Array;
    canonicalDigest: Uint8Array;
    outcome: VerificationOutcome;
    verifierBuild: string;
    observedAt: Date;
  }): Promise<PersistVerificationResult>;
}
