import {
  InvalidTransitionError,
  transitionClaim,
  transitionEvidence,
  TransitionGuardError,
  transitionPayment,
  transitionRefresh,
  transitionVerification,
} from "../src/index.ts";

function assertEquals<T>(actual: T, expected: T): void {
  if (actual !== expected) {
    throw new Error(`expected ${expected}, got ${actual}`);
  }
}

function assertThrows(
  operation: () => unknown,
  errorType: typeof InvalidTransitionError | typeof TransitionGuardError,
): void {
  try {
    operation();
  } catch (error) {
    if (error instanceof errorType) return;
    throw error;
  }

  throw new Error(`expected ${errorType.name}`);
}

Deno.test("refresh reducer follows the verified happy path", () => {
  let state = transitionRefresh("DRAFT", "FUND_START");
  state = transitionRefresh(state, "FUNDING_CONFIRMED", { fundingConfirmed: true });
  state = transitionRefresh(state, "PUBLISH");
  state = transitionRefresh(state, "CLAIM_CONFIRMED", {
    claimConfirmed: true,
    witnessSlotAvailable: true,
    refreshExpired: false,
  });
  state = transitionRefresh(state, "CHALLENGE_ISSUED", {
    challengeIssued: true,
    refreshExpired: false,
  });
  state = transitionRefresh(state, "EVIDENCE_COMMITTED", {
    evidenceCommitted: true,
    refreshExpired: false,
  });
  state = transitionRefresh(state, "VERIFICATION_STARTED", {
    evidenceSetCommitted: true,
  });
  state = transitionRefresh(state, "VERIFIED", { verificationResult: "VERIFIED" });
  state = transitionRefresh(state, "SETTLEMENT_STARTED", { settlementEligible: true });
  state = transitionRefresh(state, "PAYMENT_CONFIRMED", { paymentConfirmed: true });

  assertEquals(state, "COMPLETED");
});

Deno.test("refresh conflict requires an explicit additional-evidence path", () => {
  let state = transitionRefresh("VERIFYING", "CONFLICT_DETECTED", {
    verificationResult: "CONFLICT",
  });
  state = transitionRefresh(state, "REQUEST_ADDITIONAL_EVIDENCE");
  state = transitionRefresh(state, "NEW_CLAIM_CONFIRMED", {
    claimConfirmed: true,
    witnessSlotAvailable: true,
    refreshExpired: false,
  });

  assertEquals(state, "CLAIMED");
});

Deno.test("refresh rejects impossible and unguarded transitions", () => {
  assertThrows(
    () => transitionRefresh("DRAFT", "CLAIM_CONFIRMED"),
    InvalidTransitionError,
  );
  assertThrows(
    () =>
      transitionRefresh("AVAILABLE", "CLAIM_CONFIRMED", {
        claimConfirmed: true,
        witnessSlotAvailable: false,
      }),
    TransitionGuardError,
  );
  assertThrows(
    () => transitionRefresh("COMPLETED", "PUBLISH"),
    InvalidTransitionError,
  );
});

Deno.test("claim reducer persists authority before progressing", () => {
  let state = transitionClaim("EMPTY", "PREPARE");
  state = transitionClaim(state, "WALLET_REQUESTED");
  state = transitionClaim(state, "TRANSACTION_SUBMITTED", { transactionSubmitted: true });
  state = transitionClaim(state, "CONFIRMATION_STARTED");
  state = transitionClaim(state, "CLAIM_CONFIRMED", {
    chainClaimConfirmed: true,
    refreshExpired: false,
  });
  state = transitionClaim(state, "CAPTURE_STARTED", {
    challengeIssued: true,
    refreshExpired: false,
  });
  state = transitionClaim(state, "EVIDENCE_COMMITTED", {
    evidenceCommittedBeforeDeadline: true,
  });

  assertEquals(state, "EVIDENCE_COMMITTED");
});

Deno.test("claim release cannot race past committed evidence", () => {
  assertThrows(
    () =>
      transitionClaim("CAPTURE_ACTIVE", "CLAIM_DEADLINE_REACHED", {
        claimDeadlineReached: true,
        qualifyingEvidenceCommitted: true,
      }),
    TransitionGuardError,
  );

  let state = transitionClaim("CLAIMED", "CLAIM_DEADLINE_REACHED", {
    claimDeadlineReached: true,
    qualifyingEvidenceCommitted: false,
  });
  state = transitionClaim(state, "RELEASE_CONFIRMED", { releaseConfirmed: true });
  assertEquals(state, "RELEASED");
});

Deno.test("evidence reducer reuses the same evidence after transient upload failure", () => {
  let state = transitionEvidence("NONE", "CAMERA_START", {
    activeClaim: true,
    validChallenge: true,
    refreshValid: true,
  });
  state = transitionEvidence(state, "CAPTURE_OK", { captureCompleted: true });
  state = transitionEvidence(state, "HASH_START");
  state = transitionEvidence(state, "HASH_OK", { digestAvailable: true });
  state = transitionEvidence(state, "NETWORK_AVAILABLE", { uploadAllowed: true });
  state = transitionEvidence(state, "TRANSIENT_FAILURE");
  assertEquals(state, "UPLOAD_READY");

  state = transitionEvidence(state, "NETWORK_AVAILABLE", { uploadAllowed: true });
  state = transitionEvidence(state, "UPLOAD_OK", { uploadConfirmed: true });
  state = transitionEvidence(state, "COMMIT");
  state = transitionEvidence(state, "COMMIT_OK", { commitAccepted: true });
  state = transitionEvidence(state, "VERIFY", { serverCommitObserved: true });
  state = transitionEvidence(state, "PASS", { verificationResult: "VERIFIED" });

  assertEquals(state, "VERIFIED");
});

Deno.test("evidence cannot become verified from an uncommitted upload", () => {
  assertThrows(
    () => transitionEvidence("UPLOADED", "PASS", { verificationResult: "VERIFIED" }),
    InvalidTransitionError,
  );
});

Deno.test("verification retry preserves evidence and policy identity", () => {
  let state = transitionVerification("NOT_STARTED", "EVIDENCE_COMMITTED", {
    evidenceCommitted: true,
  });
  state = transitionVerification(state, "START", {
    evidenceSetFrozen: true,
    policySnapshotValid: true,
  });
  state = transitionVerification(state, "INTERNAL_ERROR");
  state = transitionVerification(state, "RETRY_SAME_OPERATION", {
    sameEvidenceSet: true,
    samePolicyVersion: true,
    refreshExpired: false,
  });

  assertEquals(state, "QUEUED");

  assertThrows(
    () =>
      transitionVerification("INTERNAL_ERROR", "RETRY_SAME_OPERATION", {
        sameEvidenceSet: false,
        samePolicyVersion: true,
      }),
    TransitionGuardError,
  );
});

Deno.test("verification outcomes are terminal for the current result", () => {
  const state = transitionVerification("RUNNING", "PASS", { result: "VERIFIED" });
  assertEquals(state, "VERIFIED");
  assertThrows(
    () => transitionVerification(state, "START"),
    InvalidTransitionError,
  );
});

Deno.test("payment ambiguity reconciles before retry", () => {
  let state = transitionPayment("NOT_STARTED", "START_SETTLEMENT", {
    verificationVerified: true,
    escrowLocked: true,
    refreshExpired: false,
    claimantsMatch: true,
    verifierPinned: true,
  });
  state = transitionPayment(state, "OUTCOME_AMBIGUOUS");
  assertEquals(state, "VERIFYING");

  assertThrows(
    () => transitionPayment(state, "RETRY_SETTLEMENT", { retryAuthorized: true }),
    InvalidTransitionError,
  );

  state = transitionPayment(state, "PROVEN_NOT_SETTLED", {
    settlementDefinitivelyAbsent: true,
  });
  assertThrows(
    () =>
      transitionPayment(state, "RETRY_SETTLEMENT", {
        retryAuthorized: true,
        settlementDefinitivelyAbsent: true,
        sameOperationIdentity: false,
      }),
    TransitionGuardError,
  );

  state = transitionPayment(state, "RETRY_SETTLEMENT", {
    retryAuthorized: true,
    settlementDefinitivelyAbsent: true,
    sameOperationIdentity: true,
  });

  assertEquals(state, "PENDING");
});

Deno.test("payment ambiguity resolves only from a matching confirmed chain outcome", () => {
  let state = transitionPayment("PENDING", "OUTCOME_AMBIGUOUS");
  assertEquals(state, "VERIFYING");

  assertThrows(
    () =>
      transitionPayment(state, "PAYMENT_CONFIRMED", {
        chainConfirmed: true,
        settlementDigestMatches: false,
      }),
    TransitionGuardError,
  );

  state = transitionPayment(state, "PAYMENT_CONFIRMED", {
    chainConfirmed: true,
    settlementDigestMatches: true,
  });
  assertEquals(state, "PAID");

  assertThrows(
    () => transitionPayment(state, "RETRY_SETTLEMENT"),
    InvalidTransitionError,
  );
});

Deno.test("payment only reaches PAID after matching confirmed settlement", () => {
  assertThrows(
    () =>
      transitionPayment("PENDING", "PAYMENT_CONFIRMED", {
        chainConfirmed: true,
        settlementDigestMatches: false,
      }),
    TransitionGuardError,
  );

  const state = transitionPayment("PENDING", "PAYMENT_CONFIRMED", {
    chainConfirmed: true,
    settlementDigestMatches: true,
  });
  assertEquals(state, "PAID");

  assertThrows(
    () => transitionPayment(state, "RETRY_SETTLEMENT"),
    InvalidTransitionError,
  );
});
