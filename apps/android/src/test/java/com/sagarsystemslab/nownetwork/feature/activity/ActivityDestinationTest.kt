package com.sagarsystemslab.nownetwork.feature.activity

import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityDestinationTest {
    private fun destination(claim: String, payment: String = "", receipt: String = "") = activityDestination(receipt, payment, claim, "acceptance", "AVAILABLE")

    @Test fun captureAndSubmittedEvidenceHaveDifferentRecoveryDestinations() {
        assertEquals(ActivityDestination.CAPTURE, destination("CAPTURE_ACTIVE"))
        assertEquals(ActivityDestination.VERIFICATION, destination("EVIDENCE_COMMITTED"))
        assertEquals(ActivityDestination.CLAIM, destination("UNKNOWN"))
    }
    @Test fun FinalReceiptAndPaymentOverrideEarlierClaimStatus() {
        assertEquals(ActivityDestination.RECEIPT, destination("CLAIMED", "FINALIZED", "receipt"))
        assertEquals(ActivityDestination.PAYMENT, destination("EVIDENCE_COMMITTED", "VERIFYING"))
        assertEquals(ActivityDestination.VERIFICATION, destination("EVIDENCE_COMMITTED", "NOT_STARTED"))
    }
}
