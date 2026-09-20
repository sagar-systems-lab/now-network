import type { VerificationStatus } from "../../contracts/src/lifecycle.ts";
import type { VerificationResult } from "../../contracts/src/core.ts";
import { nextState, requireGuard, type TransitionTable } from "./state-machine.ts";

export type VerificationEvent =
  | "EVIDENCE_COMMITTED"
  | "START"
  | "PASS"
  | "REJECT"
  | "CONFLICT"
  | "REQUIRE_MORE_EVIDENCE"
  | "NEW_EVIDENCE_COMMITTED"
  | "INTERNAL_ERROR"
  | "RETRY_SAME_OPERATION"
  | "EXPIRE";

export interface VerificationTransitionFacts {
  evidenceCommitted?: boolean;
  evidenceSetFrozen?: boolean;
  policySnapshotValid?: boolean;
  result?: VerificationResult;
  sameEvidenceSet?: boolean;
  samePolicyVersion?: boolean;
  refreshExpired?: boolean;
}

const transitions: TransitionTable<VerificationStatus, VerificationEvent> = {
  NOT_STARTED: {
    EVIDENCE_COMMITTED: "QUEUED",
    EXPIRE: "EXPIRED",
  },
  QUEUED: {
    START: "RUNNING",
    EXPIRE: "EXPIRED",
  },
  RUNNING: {
    PASS: "VERIFIED",
    REJECT: "REJECTED",
    CONFLICT: "CONFLICT",
    REQUIRE_MORE_EVIDENCE: "WAITING_FOR_MORE_EVIDENCE",
    INTERNAL_ERROR: "INTERNAL_ERROR",
    EXPIRE: "EXPIRED",
  },
  WAITING_FOR_MORE_EVIDENCE: {
    NEW_EVIDENCE_COMMITTED: "QUEUED",
    EXPIRE: "EXPIRED",
  },
  INTERNAL_ERROR: {
    RETRY_SAME_OPERATION: "QUEUED",
    EXPIRE: "EXPIRED",
  },
  VERIFIED: {},
  REJECTED: {},
  CONFLICT: {},
  EXPIRED: {},
};

function guardVerificationTransition(
  state: VerificationStatus,
  event: VerificationEvent,
  facts: VerificationTransitionFacts,
): void {
  const guard = (condition: boolean, reason: string) =>
    requireGuard(condition, "verification", state, event, reason);

  switch (event) {
    case "EVIDENCE_COMMITTED":
    case "NEW_EVIDENCE_COMMITTED":
      guard(facts.evidenceCommitted === true, "verification requires committed evidence");
      break;
    case "START":
      guard(facts.evidenceSetFrozen === true, "evidence set must be frozen for this operation");
      guard(facts.policySnapshotValid === true, "policy snapshot must be valid");
      break;
    case "PASS":
      guard(facts.result === "VERIFIED", "result must be VERIFIED");
      break;
    case "REJECT":
      guard(facts.result === "REJECTED", "result must be REJECTED");
      break;
    case "CONFLICT":
      guard(facts.result === "CONFLICT", "result must be CONFLICT");
      break;
    case "REQUIRE_MORE_EVIDENCE":
      guard(
        facts.result === "REQUIRES_ADDITIONAL_VERIFICATION",
        "result must require additional verification",
      );
      break;
    case "RETRY_SAME_OPERATION":
      guard(facts.sameEvidenceSet === true, "retry must use the same evidence set");
      guard(facts.samePolicyVersion === true, "retry must use the same policy version");
      guard(facts.refreshExpired !== true, "expired refresh cannot retry verification");
      break;
    case "EXPIRE":
      guard(facts.refreshExpired === true, "refresh deadline must have passed");
      break;
  }
}

export function transitionVerification(
  current: VerificationStatus,
  event: VerificationEvent,
  facts: VerificationTransitionFacts = {},
): VerificationStatus {
  const target = nextState("verification", transitions, current, event);
  guardVerificationTransition(current, event, facts);
  return target;
}
