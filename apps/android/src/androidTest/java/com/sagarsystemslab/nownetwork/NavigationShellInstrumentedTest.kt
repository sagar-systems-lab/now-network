package com.sagarsystemslab.nownetwork

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationShellInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun bottomNavigationMovesAcrossTheThreePrimaryIntents() {
        composeRule.onNodeWithTag("screen-now").assertIsDisplayed()

        composeRule.onNodeWithTag("nav-earn").performClick()
        composeRule.onNodeWithTag("screen-earn").assertIsDisplayed()

        composeRule.onNodeWithTag("nav-activity").performClick()
        composeRule.onNodeWithTag("screen-activity").assertIsDisplayed()

        composeRule.onNodeWithTag("nav-now").performClick()
        composeRule.onNodeWithTag("screen-now").assertIsDisplayed()
    }
}
