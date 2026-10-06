package com.sagarsystemslab.nownetwork.feature.payment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sagarsystemslab.nownetwork.repository.PaymentFailure
import com.sagarsystemslab.nownetwork.repository.PaymentOutcome
import com.sagarsystemslab.nownetwork.repository.PaymentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

enum class PaymentStage {
    PENDING,
    VERIFYING,
    PAID,
    FAILED,
    ATTENTION,
}

data class PaymentUiState(
    val stage: PaymentStage = PaymentStage.PENDING,
    val refreshId: String? = null,
    val settlementId: String? = null,
    val settlementStatus: String = "NOT_STARTED",
    val chainSignature: String? = null,
    val chainCommitment: String? = null,
    val confirmedAt: String? = null,
    val finalizedAt: String? = null,
    val message: String = "Payment is queued.",
)

@HiltViewModel
class PaymentViewModel @Inject constructor(
    private val repository: PaymentRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(PaymentUiState())
    val state: StateFlow<PaymentUiState> = mutableState.asStateFlow()

    private var trackingJob: Job? = null

    fun open(refreshId: String) {
        if (
            mutableState.value.refreshId == refreshId &&
            trackingJob?.isActive == true
        ) {
            return
        }
        mutableState.value = PaymentUiState(refreshId = refreshId)
        startTracking(refreshId)
    }

    fun retry() {
        val refreshId = mutableState.value.refreshId ?: return
        startTracking(refreshId)
    }

    private fun startTracking(refreshId: String) {
        trackingJob?.cancel()
        trackingJob = viewModelScope.launch {
            try {
                withTimeout(TRACKING_WINDOW_MS) {
                    repository.prime(refreshId)
                    while (true) {
                        try {
                            val outcome = repository.check(refreshId)
                            apply(outcome)
                            if (
                                mutableState.value.stage == PaymentStage.PAID ||
                                mutableState.value.stage == PaymentStage.FAILED
                            ) {
                                return@withTimeout
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: PaymentFailure.Retryable) {
                            val last = mutableState.value
                            mutableState.value = last.copy(
                                stage = when (last.stage) {
                                    PaymentStage.VERIFYING -> PaymentStage.VERIFYING
                                    PaymentStage.PAID -> PaymentStage.PAID
                                    PaymentStage.FAILED -> PaymentStage.FAILED
                                    else -> PaymentStage.PENDING
                                },
                                message = if (last.stage == PaymentStage.VERIFYING) {
                                    "Connection interrupted. NOW is still reconciling the previous settlement before any retry."
                                } else {
                                    "Connection interrupted. Your payment state is saved and will be checked again."
                                },
                            )
                        } catch (error: PaymentFailure) {
                            mutableState.value = mutableState.value.copy(
                                stage = PaymentStage.ATTENTION,
                                message = error.message
                                    ?: "Payment status could not be confirmed safely.",
                            )
                            return@withTimeout
                        } catch (_: Exception) {
                            mutableState.value = mutableState.value.copy(
                                stage = PaymentStage.ATTENTION,
                                message = "Payment status could not be confirmed safely.",
                            )
                            return@withTimeout
                        }

                        delay(POLL_INTERVAL_MS)
                    }
                }
            } catch (_: TimeoutCancellationException) {
                val last = mutableState.value
                if (last.stage != PaymentStage.PAID && last.stage != PaymentStage.FAILED) {
                    mutableState.value = last.copy(
                        stage = PaymentStage.ATTENTION,
                        message = "Payment is still pending. Automatic tracking paused after 30 seconds; tap Check payment status to continue. No second transfer is sent.",
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: PaymentFailure) {
                mutableState.value = mutableState.value.copy(
                    stage = PaymentStage.ATTENTION,
                    message = error.message
                        ?: "Payment tracking could not be restored.",
                )
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    stage = PaymentStage.ATTENTION,
                    message = "Payment tracking could not be restored.",
                )
            }
        }
    }

    private fun apply(outcome: PaymentOutcome) {
        val stage = when (outcome.paymentStatus) {
            "PENDING" -> PaymentStage.PENDING
            "VERIFYING" -> PaymentStage.VERIFYING
            "PAID" -> PaymentStage.PAID
            "FAILED" -> PaymentStage.FAILED
            else -> PaymentStage.ATTENTION
        }

        val message = when (stage) {
            PaymentStage.PENDING ->
                if (outcome.settlementStatus == "NOT_SETTLED") {
                    "The previous settlement attempt was proven absent. A safe retry may proceed with the same payment authority."
                } else {
                    "Verification succeeded. Settlement is queued and no action is required."
                }

            PaymentStage.VERIFYING ->
                "NOW is checking Solana before allowing any retry. No action is needed yet."

            PaymentStage.PAID ->
                if (outcome.chainCommitment == "finalized") {
                    "Payment is finalized on Solana."
                } else {
                    "Payment is confirmed on Solana."
                }

            PaymentStage.FAILED ->
                "Settlement stopped after an authority or invariant failure. NOW will not guess or retry blindly."

            PaymentStage.ATTENTION ->
                "Payment status could not be confirmed safely."
        }

        mutableState.value = PaymentUiState(
            stage = stage,
            refreshId = outcome.refreshId,
            settlementId = outcome.settlementId,
            settlementStatus = outcome.settlementStatus,
            chainSignature = outcome.chainSignature,
            chainCommitment = outcome.chainCommitment,
            confirmedAt = outcome.confirmedAt,
            finalizedAt = outcome.finalizedAt,
            message = message,
        )
    }

    private companion object {
        const val POLL_INTERVAL_MS = 2_000L
        const val TRACKING_WINDOW_MS = 30_000L
    }
}
