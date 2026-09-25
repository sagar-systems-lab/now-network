import type { VerificationClass } from "../../packages/contracts/src/core.ts";
import type {
  RefreshStatus,
  StateType,
} from "../../packages/contracts/src/lifecycle.ts";
import type { PolicyTemplateV1 } from "../../packages/policy/src/types.ts";

export type StateProjectionContext = {
  refreshId: string;
  refreshStatus: RefreshStatus;
  refreshExpiresAt: Date;
  stateId: string;
  stateVersion: number;
  stateType: StateType;
  unitCode: string | null;
  verificationClass: VerificationClass;
  refreshExecutionHash: Uint8Array | null;
  proofPolicySnapshot: PolicyTemplateV1;
  verificationResultId: string;
  verificationStatus: "VERIFIED";
  verificationExecutionHash: Uint8Array;
  finalAnswer: unknown;
  matchingEvidenceIds: string[];
  completedAt: Date;
  observationEarliest: Date;
  observationLatest: Date;
};

type StateProjectionRecord = {
    stateId: string;
    refreshId: string;
    verificationResultId: string;
    historyId: string;
    stateRevision: number;
    observedAt: Date;
    agingAt: Date;
    freshUntil: Date;
    currentValue: unknown;
};

export type StateProjectionResult =
  | ({ kind: "projected" } & StateProjectionRecord)
  | ({ kind: "replayed" } & StateProjectionRecord)
  | ({ kind: "superseded" } & StateProjectionRecord)
  | { kind: "not_found" }
  | { kind: "not_verified" }
  | { kind: "expired" }
  | { kind: "authority_conflict" };

export interface StateProjectionRepository {
  getContext(
    refreshId: string,
    verificationResultId: string,
  ): Promise<StateProjectionContext | null>;

  project(input: {
    refreshId: string;
    verificationResultId: string;
    stateId: string;
    stateVersion: number;
    currentValue: unknown;
    currentValueDigest: Uint8Array;
    observedAt: Date;
    observationEarliest: Date;
    observationLatest: Date;
    agingAt: Date;
    freshUntil: Date;
    verificationClass: VerificationClass;
    executionHash: Uint8Array;
  }): Promise<StateProjectionResult>;
}
