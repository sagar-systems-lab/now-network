import { nextState, requireGuard, type TransitionTable } from "./state-machine.ts";

export type ClaimStatus =
  | "EMPTY"
  | "PREPARING"
  | "WALLET_PENDING"
  | "SUBMITTED"
  | "CONFIRMING"
  | "CLAIMED"
  | "CAPTURE_ACTIVE"
  | "EVIDENCE_COMMITTED"
  | "RELEASE_ELIGIBLE"
  | "RELEASED"
  | "EXPIRED"
  | "FAILED"
  | "UNKNOWN";

export type ClaimEvent =
  | "PREPARE"
  | "WALLET_REQUESTED"
  | "WALLET_REJECTED"
  | "TRANSACTION_SUBMITTED"
  | "CONFIRMATION_STARTED"
  | "OUTCOME_UNKNOWN"
  | "CLAIM_CONFIRMED"
  | "CAPTURE_STARTED"
  | "EVIDENCE_COMMITTED"
  | "CLAIM_DEADLINE_REACHED"
  | "RELEASE_CONFIRMED"
  | "RECONCILED_CLAIMED"
  | "RECONCILED_ABSENT"
  | "REFRESH_EXPIRED"
  | "TERMINAL_FAILURE";

export interface ClaimTransitionFacts {
  transactionSubmitted?: boolean;
  chainClaimConfirmed?: boolean;
  challengeIssued?: boolean;
  evidenceCommittedBeforeDeadline?: boolean;
  qualifyingEvidenceCommitted?: boolean;
  claimDeadlineReached?: boolean;
  releaseConfirmed?: boolean;
  claimDefinitivelyAbsent?: boolean;
  refreshExpired?: boolean;
}

const transitions: TransitionTable<ClaimStatus, ClaimEvent> = {
  EMPTY: {
    PREPARE: "PREPARING",
  },
  PREPARING: {
    WALLET_REQUESTED: "WALLET_PENDING",
    TERMINAL_FAILURE: "FAILED",
  },
  WALLET_PENDING: {
    TRANSACTION_SUBMITTED: "SUBMITTED",
    WALLET_REJECTED: "EMPTY",
    TERMINAL_FAILURE: "FAILED",
  },
  SUBMITTED: {
    CONFIRMATION_STARTED: "CONFIRMING",
    OUTCOME_UNKNOWN: "UNKNOWN",
    TERMINAL_FAILURE: "FAILED",
  },
  CONFIRMING: {
    CLAIM_CONFIRMED: "CLAIMED",
    OUTCOME_UNKNOWN: "UNKNOWN",
    TERMINAL_FAILURE: "FAILED",
  },
  UNKNOWN: {
    RECONCILED_CLAIMED: "CLAIMED",
    RECONCILED_ABSENT: "EMPTY",
    REFRESH_EXPIRED: "EXPIRED",
    TERMINAL_FAILURE: "FAILED",
  },
  CLAIMED: {
    CAPTURE_STARTED: "CAPTURE_ACTIVE",
    CLAIM_DEADLINE_REACHED: "RELEASE_ELIGIBLE",
    REFRESH_EXPIRED: "EXPIRED",
  },
  CAPTURE_ACTIVE: {
    EVIDENCE_COMMITTED: "EVIDENCE_COMMITTED",
    CLAIM_DEADLINE_REACHED: "RELEASE_ELIGIBLE",
    REFRESH_EXPIRED: "EXPIRED",
  },
  EVIDENCE_COMMITTED: {
    REFRESH_EXPIRED: "EXPIRED",
  },
  RELEASE_ELIGIBLE: {
    RELEASE_CONFIRMED: "RELEASED",
    REFRESH_EXPIRED: "EXPIRED",
  },
  RELEASED: {},
  EXPIRED: {},
  FAILED: {},
};

function guardClaimTransition(
  state: ClaimStatus,
  event: ClaimEvent,
  facts: ClaimTransitionFacts,
): void {
  const guard = (condition: boolean, reason: string) =>
    requireGuard(condition, "claim", state, event, reason);

  switch (event) {
    case "WALLET_REJECTED":
      guard(\n        facts.transactionSubmitted !== true,\n        "wallet rejection is valid only before submission",\n      );
      break;
    case "TRANSACTION_SUBMITTED":
      guard(facts.transactionSubmitted === true, "submission must be persisted");
      break;
    case "CLAIM_CONFIRMED":
    case "RECONCILED_CLAIMED":
      guard(facts.chainClaimConfirmed === true, "claim must be confirmed by authority");
      guard(facts.refreshExpired !== true, "refresh must not be expired");
      break;
    case "RECONCILED_ABSENT":
      guard(facts.claimDefinitivelyAbsent === true, "absence must be proven before reuse");
      break;
    case "CAPTURE_STARTED":
      guard(facts.challengeIssued === true, "capture requires a valid challenge");
      guard(facts.refreshExpired !== true, "refresh must not be expired");
      break;
    case "EVIDENCE_COMMITTED":
      guard(
        facts.evidenceCommittedBeforeDeadline === true,
        "evidence must be committed within the allowed claim window",
      );
      break;
    case "CLAIM_DEADLINE_REACHED":
      guard(facts.claimDeadlineReached === true, "claim deadline must have passed");
      guard(
        facts.qualifyingEvidenceCommitted !== true,
        "claim cannot release after qualifying evidence committed",
      );
      break;
    case "RELEASE_CONFIRMED":
      guard(facts.releaseConfirmed === true, "claim release must be confirmed");
      break;
    case "REFRESH_EXPIRED":
      guard(facts.refreshExpired === true, "refresh deadline must have passed");
      break;
  }
}

export function transitionClaim(
  current: ClaimStatus,
  event: ClaimEvent,
  facts: ClaimTransitionFacts = {},
): ClaimStatus {
  const target = nextState("claim", transitions, current, event);
  guardClaimTransition(current, event, facts);
  return target;
}
