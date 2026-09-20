import type { EvidenceStatus } from "../../contracts/src/lifecycle.ts";
import type { VerificationResult } from "../../contracts/src/core.ts";
import { nextState, requireGuard, type TransitionTable } from "./state-machine.ts";

export type EvidenceEvent =
  | "CAMERA_START"
  | "CAPTURE_OK"
  | "CANCEL"
  | "HASH_START"
  | "HASH_OK"
  | "LOCAL_FILE_ERROR"
  | "NETWORK_AVAILABLE"
  | "UPLOAD_OK"
  | "TRANSIENT_FAILURE"
  | "TERMINAL_FAILURE"
  | "COMMIT"
  | "COMMIT_OK"
  | "IDEMPOTENT_DUPLICATE"
  | "REJECT"
  | "VERIFY"
  | "PASS"
  | "CONFLICT"
  | "EXPIRE";

export interface EvidenceTransitionFacts {
  activeClaim?: boolean;
  validChallenge?: boolean;
  refreshValid?: boolean;
  captureCompleted?: boolean;
  digestAvailable?: boolean;
  uploadAllowed?: boolean;
  uploadConfirmed?: boolean;
  commitAccepted?: boolean;
  commitAlreadyAccepted?: boolean;
  serverCommitObserved?: boolean;
  verificationResult?: VerificationResult;
  expired?: boolean;
}

const transitions: TransitionTable<EvidenceStatus, EvidenceEvent> = {
  NONE: {
    CAMERA_START: "CAPTURING",
  },
  CAPTURING: {
    CAPTURE_OK: "CAPTURED_LOCAL",
    CANCEL: "NONE",
  },
  CAPTURED_LOCAL: {
    HASH_START: "HASHING",
    EXPIRE: "EXPIRED",
  },
  HASHING: {
    HASH_OK: "UPLOAD_READY",
    LOCAL_FILE_ERROR: "FAILED",
  },
  UPLOAD_READY: {
    NETWORK_AVAILABLE: "UPLOADING",
    EXPIRE: "EXPIRED",
  },
  UPLOADING: {
    UPLOAD_OK: "UPLOADED",
    TRANSIENT_FAILURE: "UPLOAD_READY",
    TERMINAL_FAILURE: "FAILED",
  },
  UPLOADED: {
    COMMIT: "COMMITTING",
  },
  COMMITTING: {
    COMMIT_OK: "COMMITTED",
    IDEMPOTENT_DUPLICATE: "COMMITTED",
    REJECT: "REJECTED",
  },
  COMMITTED: {
    VERIFY: "VERIFYING",
  },
  VERIFYING: {
    PASS: "VERIFIED",
    REJECT: "REJECTED",
    CONFLICT: "CONFLICT",
    EXPIRE: "EXPIRED",
  },
  VERIFIED: {},
  REJECTED: {},
  CONFLICT: {},
  EXPIRED: {},
  FAILED: {},
};

function guardEvidenceTransition(
  state: EvidenceStatus,
  event: EvidenceEvent,
  facts: EvidenceTransitionFacts,
): void {
  const guard = (condition: boolean, reason: string) =>
    requireGuard(condition, "evidence", state, event, reason);

  switch (event) {
    case "CAMERA_START":
      guard(facts.activeClaim === true, "capture requires an active claim");
      guard(facts.validChallenge === true, "capture requires a valid challenge");
      guard(facts.refreshValid === true, "refresh must remain valid");
      break;
    case "CAPTURE_OK":
      guard(facts.captureCompleted === true, "capture completion must be recorded");
      break;
    case "HASH_OK":
      guard(facts.digestAvailable === true, "media digest must be available");
      break;
    case "NETWORK_AVAILABLE":
      guard(facts.uploadAllowed === true, "challenge timing must still allow upload");
      break;
    case "UPLOAD_OK":
      guard(facts.uploadConfirmed === true, "upload must be confirmed");
      break;
    case "COMMIT_OK":
      guard(facts.commitAccepted === true, "evidence commit must be accepted");
      break;
    case "IDEMPOTENT_DUPLICATE":
      guard(
        facts.commitAlreadyAccepted === true,
        "duplicate is idempotent only when the same evidence was already accepted",
      );
      break;
    case "VERIFY":
      guard(facts.serverCommitObserved === true, "verification requires committed evidence");
      break;
    case "PASS":
      guard(facts.verificationResult === "VERIFIED", "verification result must be VERIFIED");
      break;
    case "CONFLICT":
      guard(facts.verificationResult === "CONFLICT", "verification result must be CONFLICT");
      break;
    case "EXPIRE":
      guard(facts.expired === true, "evidence or challenge deadline must have passed");
      break;
  }
}

export function transitionEvidence(
  current: EvidenceStatus,
  event: EvidenceEvent,
  facts: EvidenceTransitionFacts = {},
): EvidenceStatus {
  const target = nextState("evidence", transitions, current, event);
  guardEvidenceTransition(current, event, facts);
  return target;
}
