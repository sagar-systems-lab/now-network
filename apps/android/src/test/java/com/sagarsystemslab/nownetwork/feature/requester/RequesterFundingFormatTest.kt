package com.sagarsystemslab.nownetwork.feature.requester

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RequesterFundingFormatTest {
    @Test
    fun decimalRewardConvertsToExactAtomicUnits() {
        assertEquals(
            "450000",
            parseRewardInput(
                input = "0.45",
                decimals = 6,
            ),
        )
    }

    @Test
    fun rewardInputRejectsPrecisionLossAndNonPositiveValues() {
        assertNull(parseRewardInput("0.0000001", 6))
        assertNull(parseRewardInput("0", 6))
        assertNull(parseRewardInput("-1", 6))
    }

    @Test
    fun persistedAtomicAmountRestoresHumanInput() {
        assertEquals(
            "0.45",
            formatAtomicInput(
                atomic = "450000",
                decimals = 6,
            ),
        )
    }
}
