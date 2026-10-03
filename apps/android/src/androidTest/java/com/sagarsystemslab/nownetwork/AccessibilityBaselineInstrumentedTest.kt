package com.sagarsystemslab.nownetwork

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
            .onNodeWithContentDescription("Refresh nearby")
            .assertIsDisplayed()
            .assertHasClickAction()

        composeRule
            .onNodeWithContentDescription("Open profile")
            .assertIsDisplayed()
            .assertHasClickAction()

        composeRule.onNodeWithTag("nav-earn").performClick()

        composeRule.onNodeWithTag("screen-earn").assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription("Refresh nearby")
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    @Test
    fun appearanceRemainsAccessibleFromNow() {
        composeRule
            .onNodeWithContentDescription("Open profile")
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()

        composeRule.onNodeWithTag("screen-profile").assertIsDisplayed()

        composeRule
            .onNodeWithTag("Settings")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()

        composeRule.onNodeWithTag("screen-settings").assertIsDisplayed()

        composeRule
            .onNodeWithTag("Appearance")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()

        composeRule.onNodeWithTag("screen-appearance").assertIsDisplayed()

        composeRule
            .onNodeWithTag("System")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()

        composeRule
            .onNodeWithTag("Dark")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()

        composeRule
            .onNodeWithTag("Light")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()

        repeat(3) {
            composeRule
                .onNodeWithContentDescription("Back")
                .performScrollTo()
                .assertIsDisplayed()
                .assertHasClickAction()
                .performClick()
        }

        composeRule.onNodeWithTag("screen-now").assertIsDisplayed()
    }
}
