package com.sagarsystemslab.nownetwork

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityBaselineInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun primaryRefreshControlsExposeMeaningfulLabels() {
        composeRule
            .onNodeWithContentDescription("Refresh nearby states")
            .assertIsDisplayed()

        composeRule.onNodeWithTag("nav-earn").performClick()

        composeRule
            .onNodeWithContentDescription("Refresh earning opportunities")
            .assertIsDisplayed()
    }
}
