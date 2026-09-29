package com.sagarsystemslab.nownetwork.feature.receipt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sagarsystemslab.nownetwork.repository.FinalReceipt
import com.sagarsystemslab.nownetwork.repository.ReceiptFailure
import com.sagarsystemslab.nownetwork.repository.ReceiptLoadResult
import com.sagarsystemslab.nownetwork.repository.ReceiptRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ReceiptStage {
    FINALIZING,
    READY,
    ATTENTION,
}

data class ReceiptUiState(
    val stage: ReceiptStage = ReceiptStage.FINALIZING,
    val refreshId: String? = null,
    val receipt: FinalReceipt? = null,
    val message: String = "Final receipt is being prepared.",
)

@HiltViewModel
class ReceiptViewModel @Inject constructor(
    private val repository: ReceiptRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ReceiptUiState())
    val state: StateFlow<ReceiptUiState> = mutableState.asStateFlow()

    private var trackingJob: Job? = null

    fun open(refreshId: String) {
        if (
            mutableState.value.refreshId == refreshId &&
            trackingJob?.isActive == true
        ) {
            return
        }
        mutableState.value = ReceiptUiState(refreshId = refreshId)
        startTracking(refreshId)
    }

    fun retry() {
        val refreshId = mutableState.value.refreshId ?: return
        startTracking(refreshId)
    }

    private fun startTracking(refreshId: String) {
        trackingJob?.cancel()
        trackingJob = viewModelScope.launch {
            while (true) {
                try {
                    when (val result = repository.load(refreshId)) {
                        is ReceiptLoadResult.Finalizing -> {
                            mutableState.value = ReceiptUiState(
                                stage = ReceiptStage.FINALIZING,
                                refreshId = result.refreshId,
                                message = result.reason,
                            )
                        }

                        is ReceiptLoadResult.Ready -> {
                            mutableState.value = ReceiptUiState(
                                stage = ReceiptStage.READY,
                                refreshId = result.receipt.refreshId,
                                receipt = result.receipt,
                                message = "Final receipt is ready.",
                            )
                            return@launch
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: ReceiptFailure.Retryable) {
                    mutableState.value = mutableState.value.copy(
                        stage = ReceiptStage.FINALIZING,
                        message = "Connection interrupted. Final receipt state is safe and will be checked again.",
                    )
                } catch (error: ReceiptFailure) {
                    mutableState.value = mutableState.value.copy(
                        stage = ReceiptStage.ATTENTION,
                        message = error.message
                            ?: "Final receipt could not be verified safely.",
                    )
                    return@launch
                } catch (_: Exception) {
                    mutableState.value = mutableState.value.copy(
                        stage = ReceiptStage.ATTENTION,
                        message = "Final receipt could not be verified safely.",
                    )
                    return@launch
                }

                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 2_000L
    }
}
