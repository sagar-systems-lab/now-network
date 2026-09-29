package com.sagarsystemslab.nownetwork

import com.sagarsystemslab.nownetwork.navigation.EvidenceCaptureRoute
import com.sagarsystemslab.nownetwork.navigation.OpportunityRoute
import com.sagarsystemslab.nownetwork.navigation.PaymentRoute
import com.sagarsystemslab.nownetwork.navigation.ReceiptRoute
import com.sagarsystemslab.nownetwork.navigation.StateDetailRoute
import com.sagarsystemslab.nownetwork.navigation.VerificationRoute
import com.sagarsystemslab.nownetwork.navigation.TopLevelDestination
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationContractTest {
    @Test
    fun topLevelDestinationsMatchProductIntent() {
        assertEquals(
            listOf("NOW", "EARN", "ACTIVITY"),
            TopLevelDestination.entries.map { it.label },
        )
    }

    @Test
    fun contextualRoutesCarryStableIdsOnly() {
        assertEquals(
            "state-123",
            StateDetailRoute(stateId = "state-123").stateId,
        )
        assertEquals(
            "refresh-456",
            OpportunityRoute(refreshId = "refresh-456").refreshId,
        )
        val evidenceRoute = EvidenceCaptureRoute(
            acceptanceId = "acceptance-789",
            refreshId = "refresh-456",
        )
        assertEquals("acceptance-789", evidenceRoute.acceptanceId)
        assertEquals("refresh-456", evidenceRoute.refreshId)
        assertEquals(
            "refresh-456",
            VerificationRoute(refreshId = "refresh-456").refreshId,
        )
        assertEquals(
            "refresh-456",
            PaymentRoute(refreshId = "refresh-456").refreshId,
        )
        assertEquals(
            "refresh-456",
            ReceiptRoute(refreshId = "refresh-456").refreshId,
        )
    }
}
