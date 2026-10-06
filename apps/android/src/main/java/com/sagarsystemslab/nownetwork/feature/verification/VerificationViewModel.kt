package com.sagarsystemslab.nownetwork.feature.verification

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sagarsystemslab.nownetwork.repository.PaymentRepository
import com.sagarsystemslab.nownetwork.repository.VerificationFailure
import com.sagarsystemslab.nownetwork.repository.VerificationOutcome
import com.sagarsystemslab.nownetwork.repository.VerificationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement

enum class VerificationStage {
    VERIFYING,
    VERIFIED,
    MORE_EVIDENCE,
    CONFLICT,
    REJECTED,
    EXPIRED,
    ERROR,
}

data class VerificationUiState(
    val stage: VerificationStage = VerificationStage.VERIFYING,
    val refreshId: String? = null,
    val verificationResultId: String? = null,
    val evidenceCount: Int = 0,
    val evidenceSetRevision: Int = 0,
    val policyVersion: Int = 0,
    val reasonCodes: List<String> = emptyList(),
    val finalAnswer: JsonElement? = null,
    val projectedValue: JsonElement? = null,
    val projectedFreshness: String? = null,
    val projectedStateRevision: Long? = null,
    val projectionSuperseded: Boolean = false,
    val replayed: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class VerificationViewModel @Inject constructor(
    private val repository: VerificationRepository,
    private val paymentRepository: PaymentRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(VerificationUiState())
    val state: StateFlow<VerificationUiState> = mutableState.asStateFlow()

    fun open(refreshId: String) {
        verify(refreshId)
    }

    fun retry() {
        val refreshId = mutableState.value.refreshId ?: return
        verify(refreshId)
    }

    private fun verify(refreshId: String) {
        mutableState.value = VerificationUiState(
            stage = VerificationStage.VERIFYING,
            refreshId = refreshId,
            message = "Checking the committed evidence against the verification policy…",
        )

        viewModelScope.launch {
            try {
                val outcome = withTimeout(OPERATION_WAIT_MS) { repository.verify(refreshId) }
                if (outcome.result == "VERIFIED") {
                    paymentRepository.prime(outcome.refreshId)
                }
                apply(outcome)
            } catch (_: TimeoutCancellationException) {
                mutableState.value = mutableState.value.copy(
                    stage = VerificationStage.ERROR,
                    message = "Verification check timed out after 30 seconds. Tap Check verification to retry; no duplicate proof is submitted.",
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: VerificationFailure.Expired) {
                mutableState.value = mutableState.value.copy(
                    stage = VerificationStage.EXPIRED,
                    message = "The refresh expired before verification completed.",
                )
            } catch (error: VerificationFailure) {
                mutableState.value = mutableState.value.copy(
                    stage = VerificationStage.ERROR,
                    message = error.message ?: "Verification result is unavailable.",
                )
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    stage = VerificationStage.ERROR,
                    message = "Verification result could not be confirmed safely.",
                )
            }
        }
    }

    private companion object {
        const val OPERATION_WAIT_MS = 30_000L
    }

    private fun apply(outcome: VerificationOutcome) {
        val stage = when (outcome.result) {
            "VERIFIED" -> VerificationStage.VERIFIED
            "REQUIRES_ADDITIONAL_VERIFICATION" -> VerificationStage.MORE_EVIDENCE
            "CONFLICT" -> VerificationStage.CONFLICT
            "REJECTED" -> VerificationStage.REJECTED
            "EXPIRED" -> VerificationStage.EXPIRED
            else -> VerificationStage.ERROR
        }

        val projection = outcome.projection
        val message = when (stage) {
            VerificationStage.VERIFIED ->
                if (projection?.superseded == true) {
                    "Evidence verified. A newer observation already superseded this projection."
                } else {
                    "Evidence verified and the live state projection is confirmed."
                }

            VerificationStage.MORE_EVIDENCE ->
                "The current evidence set is valid but not sufficient yet. More verification is required."

            VerificationStage.CONFLICT ->
                "Valid reports disagree. NOW will not publish a false live result or settle payment."

            VerificationStage.REJECTED ->
                "The submitted evidence did not produce a policy-valid verification result."

            VerificationStage.EXPIRED ->
                "The refresh expired before verification could complete."

            VerificationStage.ERROR ->
                "Verification returned an unsupported result."

            VerificationStage.VERIFYING -> null
        }

        mutableState.value = VerificationUiState(
            stage = stage,
            refreshId = outcome.refreshId,
            verificationResultId = outcome.verificationResultId,
            evidenceCount = outcome.evidenceIds.size,
            evidenceSetRevision = outcome.evidenceSetRevision,
            policyVersion = outcome.policyVersion,
            reasonCodes = outcome.reasonCodes,
            finalAnswer = outcome.finalAnswer,
            projectedValue = projection?.currentValue,
            projectedFreshness = projection?.freshness,
            projectedStateRevision = projection?.stateRevision,
            projectionSuperseded = projection?.superseded == true,
            replayed = outcome.replayed,
            message = message,
        )
    }
}
