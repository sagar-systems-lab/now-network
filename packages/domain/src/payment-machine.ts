import type { PaymentStatus } from "../../contracts/src/core.ts";
import { nextState, requireGuard, type TransitionTable } from "./state-machine.ts";

export type PaymentEvent =
  | "START_SETTLEMENT"
  | "OUTCOME_AMBIGUOUS"
  | "PAYMENT_CONFIRMED"
  | "PROVEN_NOT_SETTLED"
  | "RETRY_SETTLEMENT"
  | "REFUND_CONFIRMED"
  | "TERMINAL_FAILURE";

export interface PaymentTransitionFacts {
  verificationVerified?: boolean;
  escrowLocked?: boolean;
  refreshExpired?: boolean;
  claimantsMatch?: boolean;
  verifierPinned?: boolean;
  settlementDigestMatches?: boolean;
  chainConfirmed?: boolean;
  settlementDefinitivelyAbsent?: boolean;
  retryAuthorized?: boolean;
  refundConfirmed?: boolean;
}

const transitions: TransitionTable<PaymentStatus, PaymentEvent> = {
  NOT_STARTED: {
    START_SETTLEMENT: "PENDING",
    REFUND_CONFIRMED: "REFUNDED",
  },
  PENDING: {
    OUTCOME_AMBIGUOUS: "VERIFYING",
    PAYMENT_CONFIRMED: "PAID",
    PROVEN_NOT_SETTLED: "NOT_SETTLED",
    REFUND_CONFIRMED: "REFUNDED",
    TERMINAL_FAILURE: "FAILED",
  },
  VERIFYING: {
    PAYMENT_CONFIRMED: "PAID",
    PROVEN_NOT_SETTLED: "NOT_SETTLED",
    REFUND_CONFIRMED: "REFUNDED",
    TERMINAL_FAILURE: "FAILED",
  },
  NOT_SETTLED: {
    RETRY_SETTLEMENT: "PENDING",
    REFUND_CONFIRMED: "REFUNDED",
    TERMINAL_FAILURE: "FAILED",
  },
  PAID: {},
  REFUNDED: {},
  FAILED: {},
};

function guardPaymentTransition(
  state: PaymentStatus,
  event: PaymentEvent,
  facts: PaymentTransitionFacts,
): void {
  const guard = (condition: boolean, reason: string) =>
    requireGuard(condition, "payment", state, event, reason);

  switch (event) {
    case "START_SETTLEMENT":
      guard(facts.verificationVerified === true, "verification must be VERIFIED");
      guard(facts.escrowLocked === true, "escrow must be locked");
      guard(facts.refreshExpired !== true, "refresh must not be expired");
      guard(facts.claimantsMatch === true, "claimants must match settlement beneficiaries");
      guard(facts.verifierPinned === true, "verifier authority must be pinned");
      break;
    case "PAYMENT_CONFIRMED":
      guard(facts.chainConfirmed === true, "chain settlement must be confirmed");
      guard(
        facts.settlementDigestMatches === true,
        "observed settlement must match the expected operation digest",
      );
      break;
    case "PROVEN_NOT_SETTLED":
      guard(
        facts.settlementDefinitivelyAbsent === true,
        "NOT_SETTLED requires proof that the prior attempt cannot land",
      );
      break;
    case "RETRY_SETTLEMENT":
      guard(facts.retryAuthorized === true, "retry must be explicitly authorized");
      guard(
        facts.settlementDefinitivelyAbsent === true,
        "retry requires proof that the prior attempt cannot land",
      );
      break;
    case "REFUND_CONFIRMED":
      guard(facts.refundConfirmed === true, "refund must be confirmed");
      break;
  }
}

export function transitionPayment(
  current: PaymentStatus,
  event: PaymentEvent,
  facts: PaymentTransitionFacts = {},
): PaymentStatus {
  const target = nextState("payment", transitions, current, event);
  guardPaymentTransition(current, event, facts);
  return target;
}
