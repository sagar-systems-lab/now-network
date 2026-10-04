package com.sagarsystemslab.nownetwork

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sagarsystemslab.nownetwork.designsystem.NowTheme
import com.sagarsystemslab.nownetwork.feature.ask.*
import com.sagarsystemslab.nownetwork.model.GeoCenter
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AskComposerInstrumentedTest {
    @get:Rule val compose=createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun selectingNeedPreservesRemotePinAndBackReturnsToPreviousStage() {
        val target=GeoCenter(28.6,77.2)
        var state by mutableStateOf(AskUiState(AskDraft("North gate",target),AskStage.NEED))
        var gpsCalls=0
        compose.setContent { NowTheme(darkTheme=false) {
            AskComposerScreen(state,{}, {},{ state=state.copy(draft=state.draft.copy(need=it)) },
                { state=state.copy(stage=it) },{ gpsCalls++ },{}, {},{})
        } }
        compose.onNodeWithText("Choose gate access").performScrollTo().performClick()
        compose.onNodeWithText("Preview request").performScrollTo().performClick()
        compose.onNodeWithText("Is this gate open or closed?").assertExists()
        compose.runOnIdle { assertEquals(target,state.draft.target); assertEquals(0,gpsCalls) }
        compose.onNodeWithContentDescription("Back").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(AskStage.NEED,state.stage); assertEquals(AskNeed.GATE,state.draft.need) }
    }

    @Test fun rewardContinueIsExplicitAndDoesNotAcquireGps() {
        val state=AskUiState(AskDraft("Remote lot",GeoCenter(28.6,77.2),AskNeed.PARKING),AskStage.PREVIEW)
        var continued=0
        var gpsCalls=0
        compose.setContent { NowTheme(darkTheme=false) {
            AskComposerScreen(state,{}, {},{}, {},{ gpsCalls++ },{}, { continued++ },{})
        } }
        compose.runOnIdle { assertEquals(0,continued); assertEquals(0,gpsCalls) }
        compose.onNodeWithText("Continue to reward").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1,continued); assertEquals(0,gpsCalls) }
    }
}
