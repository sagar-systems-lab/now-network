package com.sagarsystemslab.nownetwork

import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sagarsystemslab.nownetwork.designsystem.NowTheme
import com.sagarsystemslab.nownetwork.feature.common.ExperienceHeader
import com.sagarsystemslab.nownetwork.feature.common.MapGestureFrame
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapAndLayoutInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun activityAreaStaysOnOneLineAtLargeFontSize() {
        compose.setContent {
            NowTheme(darkTheme = false) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    Box(Modifier.width(360.dp).padding(16.dp)) {
                        ExperienceHeader("ACTIVITY", "Your refreshes, proofs & earnings", "Selected map area", {}, {}, {})
                    }
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Selected map area", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        assertFalse(layouts.single().isLineEllipsized(0))
        compose.onNodeWithTag("SHARED-AREA").assertHeightIsAtLeast(48.dp).assertHasClickAction()
    }

    @Test fun mapPanAndPinchDoNotScrollThePageAndReleaseRestoresScrolling() {
        var pageScroll: androidx.compose.foundation.ScrollState? = null
        var moves = 0
        var multiTouch = false
        compose.setContent {
            val scroll = rememberScrollState()
            pageScroll = scroll
            Column(Modifier.fillMaxSize().testTag("page").verticalScroll(scroll)) {
                AndroidView(factory = { context ->
                    MapGestureFrame(context).apply {
                        addView(object : View(context) {
                            override fun onTouchEvent(event: MotionEvent): Boolean {
                                if (event.actionMasked == MotionEvent.ACTION_MOVE) moves++
                                if (event.pointerCount > 1) multiTouch = true
                                return true
                            }
                        }, android.widget.FrameLayout.LayoutParams(-1, -1))
                    }
                }, modifier = Modifier.fillMaxWidth().height(220.dp).testTag("map-gesture"))
                Text("Scroll outside the map", Modifier.fillMaxWidth().height(220.dp).testTag("outside-map"))
                Spacer(Modifier.height(1500.dp))
            }
        }
        compose.onNodeWithTag("map-gesture").performTouchInput { swipeUp() }
        compose.onNodeWithTag("map-gesture").performTouchInput {
            down(0, centerLeft + androidx.compose.ui.geometry.Offset(30f, 0f))
            down(1, centerRight - androidx.compose.ui.geometry.Offset(30f, 0f))
            moveBy(0, androidx.compose.ui.geometry.Offset(20f, -20f))
            moveBy(1, androidx.compose.ui.geometry.Offset(-20f, 20f))
            up(1); up(0)
        }
        compose.runOnIdle { assertEquals(0, pageScroll!!.value); assertTrue(moves > 0); assertTrue(multiTouch) }
        compose.onNodeWithTag("outside-map").performTouchInput { swipeUp() }
        compose.runOnIdle { assertTrue(pageScroll!!.value > 0) }
    }

    @Test fun settlementLabControlDoesNotCoverActivityAction() {
        compose.setContent { NowTheme(darkTheme = false) { SettlementRecoveryScenario({}, Modifier.fillMaxSize().safeDrawingPadding()) } }
        val activity = compose.onNodeWithText("Back to activity").fetchSemanticsNode().boundsInRoot
        val resolve = compose.onNodeWithText("Lab · resolve PAID").fetchSemanticsNode().boundsInRoot
        assertTrue(activity.bottom <= resolve.top)
        compose.onNodeWithText("Lab · resolve PAID").performClick()
        compose.onNodeWithTag("view-final-receipt").assertIsDisplayed()
    }
}
