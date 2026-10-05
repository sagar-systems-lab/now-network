package com.sagarsystemslab.nownetwork.feature.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceCaptureUiStateTest {
    @Test
    fun requiredVideoBlocksSubmissionUntilItHasBeenValidated() {
        val state = EvidenceCaptureUiState(stage=EvidenceCaptureStage.REVIEW,stateType="VISUAL",videoRequired=true)
        assertFalse(state.canSubmit)
        assertTrue(state.copy(videoReady=true,videoDurationMs=3000).canSubmit)
        assertFalse(state.copy(stage=EvidenceCaptureStage.VIDEO,videoReady=true).canSubmit)
    }
    @Test
    fun numericEvidenceRequiresAnswerAndLocationWhenPolicyRequiresIt() {
        val base = EvidenceCaptureUiState(
            stage = EvidenceCaptureStage.REVIEW,
            stateType = "NUMERIC",
            locationRequired = true,
        )

        assertFalse(base.canSubmit)
        assertFalse(base.copy(answer = "2").canSubmit)
        assertTrue(
            base.copy(
                answer = "2",
                locationSampleCount = 1,
            ).canSubmit,
        )
    }

    @Test
    fun visualEvidenceDoesNotInventAnExtraUserAnswer() {
        val state = EvidenceCaptureUiState(
            stage = EvidenceCaptureStage.REVIEW,
            stateType = "VISUAL",
            locationRequired = false,
        )

        assertTrue(state.answerReady)
        assertTrue(state.canSubmit)
    }
}
