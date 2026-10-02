package com.sagarsystemslab.nownetwork

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityBaselineInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun primaryControlsExposeLabelsAndMinimumTouchTargets() {
        composeRule.onNodeWithTag("nav-now")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)

        composeRule.onNodeWithTag("nav-earn")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)

        composeRule.onNodeWithTag("nav-activity")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)

        composeRule
            .onNodeWithContentDescription("Refresh nearby states")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)

        composeRule
            .onNodeWithContentDescription("Open settings")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)

        composeRule.onNodeWithTag("nav-earn").performClick()

        composeRule
            .onNodeWithContentDescription("Refresh earning opportunities")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun settingsAndThemeRemainAccessibleFromNow() {
        composeRule
            .onNodeWithContentDescription("Theme")
            .assertIsDisplayed()
            .performClick()

        composeRule.onNodeWithTag("screen-now").assertIsDisplayed()

        composeRule
            .onNodeWithContentDescription("Open settings")
            .performClick()

        composeRule.onNodeWithTag("screen-settings").assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription("Theme")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)

        composeRule
            .onNodeWithContentDescription("Back")
            .performClick()

        composeRule.onNodeWithTag("screen-now").assertIsDisplayed()
    }
}
