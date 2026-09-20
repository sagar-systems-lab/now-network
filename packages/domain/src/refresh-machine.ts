import type { RefreshStatus } from "../../contracts/src/lifecycle.ts";
import type { VerificationResult } from "../../contracts/src/core.ts";
import { nextState, requireGuard, type TransitionTable } from "./state-machine.ts";

export type RefreshEvent =
  | "FUND_START"
  | "CANCEL"
  | "FUNDING_CONFIRMED"
  | "FUNDING_UNKNOWN"
  | "PUBLISH"
  | "CANCEL_UNCLAIMED"
  | "CLAIM_CONFIRMED"
  | "CHALLENGE_ISSUED"
  | "CLAIM_RELEASED"
  | "EVIDENCE_COMMITTED"
  | "VERIFICATION_STARTED"
  | "VERIFIED"
  | "CONFLICT_DETECTED"
  | "MORE_EVIDENCE_REQUIRED"
  | "REJECTED_RECOVERABLE"
  | "REQUEST_ADDITIONAL_EVIDENCE"
  | "NEW_CLAIM_CONFIRMED"
  | "SETTLEMENT_STARTED"
  | "SETTLEMENT_UNKNOWN"
  | "PAYMENT_CONFIRMED"
  | "SAFE_SETTLEMENT_RETRY"
  | "UNRECOVERABLE_FAILURE"
  | "EXPIRE";

export interface RefreshTransitionFacts {
  fundingConfirmed?: boolean;
  claimConfirmed?: boolean;
  witnessSlotAvailable?: boolean;
  challengeIssued?: boolean;
  evidenceCommitted?: boolean;
  evidenceSetCommitted?: boolean;
  verificationResult?: VerificationResult;
  settlementEligible?: boolean;
  paymentConfirmed?: boolean;
  settlementDefinitivelyAbsent?: boolean;
  refreshExpired?: boolean;
}

const transitions: TransitionTable<RefreshStatus, RefreshEvent> = {
  DRAFT: {
    FUND_START: "AWAITING_FUNDING",
    CANCEL: "CANCELLED",
  },
  AWAITING_FUNDING: {
    FUNDING_CONFIRMED: "FUNDED",
    FUNDING_UNKNOWN: "AWAITING_FUNDING",
    CANCEL: "CANCELLED",
  },
  FUNDED: {
    PUBLISH: "AVAILABLE",
    CANCEL_UNCLAIMED: "CANCELLED",
    EXPIRE: "EXPIRED",
  },
  AVAILABLE: {
    CLAIM_CONFIRMED: "CLAIMED",
    CANCEL: "CANCELLED",
    EXPIRE: "EXPIRED",
  },
  CLAIMED: {
    CHALLENGE_ISSUED: "CAPTURE_IN_PROGRESS",
    CLAIM_RELEASED: "AVAILABLE",
    EXPIRE: "EXPIRED",
  },
  CAPTURE_IN_PROGRESS: {
    EVIDENCE_COMMITTED: "EVIDENCE_SUBMITTED",
    CLAIM_RELEASED: "AVAILABLE",
    EXPIRE: "EXPIRED",
  },
  EVIDENCE_SUBMITTED: {
    VERIFICATION_STARTED: "VERIFYING",
    EXPIRE: "EXPIRED",
  },
  VERIFYING: {
    VERIFIED: "VERIFIED",
    CONFLICT_DETECTED: "CONFLICT",
    MORE_EVIDENCE_REQUIRED: "ADDITIONAL_VERIFICATION",
    REJECTED_RECOVERABLE: "CLAIMED",
    EXPIRE: "EXPIRED",
    UNRECOVERABLE_FAILURE: "FAILED",
  },
  ADDITIONAL_VERIFICATION: {
    NEW_CLAIM_CONFIRMED: "CLAIMED",
    EXPIRE: "EXPIRED",
  },
  CONFLICT: {
    REQUEST_ADDITIONAL_EVIDENCE: "ADDITIONAL_VERIFICATION",
    EXPIRE: "EXPIRED",
  },
  VERIFIED: {
    SETTLEMENT_STARTED: "SETTLEMENT_PENDING",
  },
  SETTLEMENT_PENDING: {
    SETTLEMENT_UNKNOWN: "SETTLEMENT_VERIFYING",
    PAYMENT_CONFIRMED: "COMPLETED",
    EXPIRE: "EXPIRED",
  },
  SETTLEMENT_VERIFYING: {
    PAYMENT_CONFIRMED: "COMPLETED",
    SAFE_SETTLEMENT_RETRY: "SETTLEMENT_PENDING",
    UNRECOVERABLE_FAILURE: "FAILED",
  },
  COMPLETED: {},
  CANCELLED: {},
  EXPIRED: {},
  FAILED: {},
};

function guardRefreshTransition(
  state: RefreshStatus,
  event: RefreshEvent,
  facts: RefreshTransitionFacts,
): void {
  const guard = (condition: boolean, reason: string) =>
    requireGuard(condition, "refresh", state, event, reason);

  switch (event) {
    case "FUNDING_CONFIRMED":
      guard(facts.fundingConfirmed === true, "funding must be confirmed");
      break;
    case "CLAIM_CONFIRMED":
    case "NEW_CLAIM_CONFIRMED":
      guard(facts.claimConfirmed === true, "claim must be confirmed");
      guard(facts.witnessSlotAvailable === true, "witness capacity must be available");
      guard(facts.refreshExpired !== true, "refresh must not be expired");
      break;
    case "CHALLENGE_ISSUED":
      guard(facts.challengeIssued === true, "challenge must be issued");
      guard(facts.refreshExpired !== true, "refresh must not be expired");
      break;
    case "EVIDENCE_COMMITTED":
      guard(facts.evidenceCommitted === true, "evidence commit must be authoritative");
      guard(facts.refreshExpired !== true, "refresh must not be expired");
      break;
    case "VERIFICATION_STARTED":
      guard(facts.evidenceSetCommitted === true, "verification requires committed evidence");
      break;
    case "VERIFIED":
      guard(facts.verificationResult === "VERIFIED", "verification result must be VERIFIED");
      break;
    case "CONFLICT_DETECTED":
      guard(facts.verificationResult === "CONFLICT", "verification result must be CONFLICT");
      break;
    case "MORE_EVIDENCE_REQUIRED":
      guard(
        facts.verificationResult === "REQUIRES_ADDITIONAL_VERIFICATION",
        "verification must require additional evidence",
      );
      break;
    case "SETTLEMENT_STARTED":
      guard(facts.settlementEligible === true, "settlement must be eligible");
      break;
    case "PAYMENT_CONFIRMED":
      guard(facts.paymentConfirmed === true, "payment must be confirmed");
      break;
    case "SAFE_SETTLEMENT_RETRY":
      guard(
        facts.settlementDefinitivelyAbsent === true,
        "retry requires proof that the prior settlement did not execute",
      );
      break;
    case "EXPIRE":
      guard(facts.refreshExpired === true, "refresh deadline must have passed");
      break;
  }
}

export function transitionRefresh(
  current: RefreshStatus,
  event: RefreshEvent,
  facts: RefreshTransitionFacts = {},
): RefreshStatus {
  const target = nextState("refresh", transitions, current, event);
  guardRefreshTransition(current, event, facts);
  return target;
}
