package com.sagarsystemslab.nownetwork.feature.earn

import com.sagarsystemslab.nownetwork.config.RewardDisplayConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class RewardFormatTest {
    @Test
    fun configuredMintUsesHumanTokenUnits() {
        val config = RewardDisplayConfig(
            mint = "mint-a",
            symbol = "USDC",
            decimals = 6,
        )

        assertEquals(
            "0.45 USDC",
            formatReward(
                atomic = "450000",
                mint = "mint-a",
                config = config,
            ),
        )
    }

    @Test
    fun unknownMintNeverGuessesTokenIdentity() {
        val config = RewardDisplayConfig(
            mint = "mint-a",
            symbol = "USDC",
            decimals = 6,
        )

        assertEquals(
            "450000 atomic units",
            formatReward(
                atomic = "450000",
                mint = "other-mint",
                config = config,
            ),
        )
    }

    @Test
    fun expiryFormattingIsBoundedAtZero() {
        assertEquals("Expired", formatOpportunityTime(1_000L, 1_000L))
        assertEquals("45s left", formatOpportunityTime(46_000L, 1_000L))
        assertEquals("2m left", formatOpportunityTime(121_000L, 1_000L))
    }
}
