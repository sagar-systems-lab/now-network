package com.sagarsystemslab.nownetwork.feature.requester

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sagarsystemslab.nownetwork.config.RewardDisplayConfig
import com.sagarsystemslab.nownetwork.config.SolanaRuntimeConfig
import com.sagarsystemslab.nownetwork.model.StateDetail
import com.sagarsystemslab.nownetwork.repository.FundingReconciliation
import com.sagarsystemslab.nownetwork.repository.PreparedRequesterFunding
import com.sagarsystemslab.nownetwork.repository.RequesterFundingFailure
import com.sagarsystemslab.nownetwork.repository.RequesterFundingRepository
import com.sagarsystemslab.nownetwork.repository.StateRepository
import com.sagarsystemslab.nownetwork.wallet.WalletInteractionHost
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class RequesterFundingStage {
    LOADING,
    SETUP,
    PREPARING,
    REVIEW,
    SUBMITTING,
    CONFIRMING,
    WALLET_OUTCOME_UNKNOWN,
    COMPLETE,
}

data class RequesterFundingUiState(
    val stateDetail: com.sagarsystemslab.nownetwork.model.StateDetail? = null,
    val stateId: String? = null,
    val title: String = "Refresh this state",
    val stage: RequesterFundingStage = RequesterFundingStage.LOADING,
    val amountInput: String = "",
    val amountError: String? = null,
    val rewardSymbol: String = "",
    val rewardConfigured: Boolean = false,
    val network: String = "",
    val walletAddress: String? = null,
    val refreshId: String? = null,
    val operationId: String? = null,
    val expiresAt: String? = null,
    val notice: String? = null,
)

@HiltViewModel
class RequesterFundingViewModel @Inject constructor(
    private val repository: RequesterFundingRepository,
    private val stateRepository: StateRepository,
    private val rewardConfig: RewardDisplayConfig,
    private val solanaConfig: SolanaRuntimeConfig,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        RequesterFundingUiState(
            rewardSymbol = rewardConfig.symbol,
            rewardConfigured = rewardConfig.valid && rewardConfig.mint.isNotBlank(),
            network = solanaConfig.cluster,
        ),
    )
    val state: StateFlow<RequesterFundingUiState> = mutableState.asStateFlow()

    private var prepared: PreparedRequesterFunding? = null

    fun open(stateId: String) {
        prepared = null
        mutableState.value = RequesterFundingUiState(
            stateId = stateId,
            rewardSymbol = rewardConfig.symbol,
            rewardConfigured = rewardConfig.valid && rewardConfig.mint.isNotBlank(),
            network = solanaConfig.cluster,
            stage = RequesterFundingStage.LOADING,
        )

        viewModelScope.launch {
            val detail = try { withTimeout(OPERATION_WAIT_MS) { stateRepository.getState(stateId) } }
                catch (error: Exception) { if (error is CancellationException && error !is TimeoutCancellationException) throw error; null }
            mutableState.update {
                it.copy(
                    title = detail?.title ?: "Refresh this state",
                    stateDetail = detail,
                )
            }

            try {
                applyRecovery(stateId, detail, withTimeout(OPERATION_WAIT_MS) { repository.recoverLatest() })
            } catch (error: TimeoutCancellationException) {
                mutableState.update { it.copy(stage = RequesterFundingStage.SETUP, notice = "Funding check timed out. Try again.") }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(
                        stage = RequesterFundingStage.SETUP,
                        notice = error.userMessage(),
                    )
                }
            }
        }
    }

    fun updateAmount(value: String) {
        if (value.length > 24) return
        if (value.any { !it.isDigit() && it != '.' }) return

        mutableState.update {
            it.copy(
                amountInput = value,
                amountError = null,
                notice = null,
            )
        }
    }

    fun prepare(host: WalletInteractionHost) {
        val current = mutableState.value
        val stateId = current.stateId ?: return

        val atomic = parseRewardInput(
            input = current.amountInput,
            decimals = rewardConfig.decimals,
        )
        if (atomic == null) {
            mutableState.update {
                it.copy(amountError = "Enter a valid positive ${rewardConfig.symbol} amount.")
            }
            return
        }

        if (!current.rewardConfigured) {
            mutableState.update {
                it.copy(notice = "Reward token configuration is unavailable in this build.")
            }
            return
        }

        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    stage = RequesterFundingStage.PREPARING,
                    notice = null,
                    amountError = null,
                )
            }

            try {
                val existing = withTimeout(OPERATION_WAIT_MS) { repository.recoverLatest() }
                if (existing !is FundingReconciliation.None) {
                    applyRecovery(stateId, current.stateDetail, existing)
                    return@launch
                }
                val next = withTimeout(OPERATION_WAIT_MS) { repository.prepareNew(
                    host = host,
                    stateId = stateId,
                    fundingTargetAtomic = atomic,
                ) }
                prepared = next
                mutableState.update {
                    it.copy(
                        stage = RequesterFundingStage.REVIEW,
                        walletAddress = next.wallet.address,
                        refreshId = next.refresh.refreshId,
                        operationId = next.operation.operationId,
                        expiresAt = next.refresh.refreshExpiresAt,
                    )
                }
            } catch (error: TimeoutCancellationException) {
                mutableState.update { it.copy(stage = RequesterFundingStage.SETUP, notice = "Preparation timed out. Check Activity before creating another refresh.") }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(
                        stage = RequesterFundingStage.SETUP,
                        notice = error.userMessage(),
                    )
                }
            }
        }
    }

    fun submit(host: WalletInteractionHost) {
        val funding = prepared ?: return

        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    stage = RequesterFundingStage.SUBMITTING,
                    notice = null,
                )
            }

            try {
                when (val result = withTimeout(OPERATION_WAIT_MS) { repository.submit(host, funding) }) {
                    is FundingReconciliation.Available -> {
                        prepared = null
                        mutableState.update {
                            it.copy(
                                stage = RequesterFundingStage.COMPLETE,
                                refreshId = result.refresh.refreshId,
                                notice = null,
                            )
                        }
                    }

                    is FundingReconciliation.Confirming -> {
                        mutableState.update {
                            it.copy(
                                stage = RequesterFundingStage.CONFIRMING,
                                operationId = result.operation.operationId,
                                notice = "Transaction submitted. Waiting for authoritative confirmation.",
                            )
                        }
                        pollConfirmation()
                    }

                    is FundingReconciliation.ReadyForWallet -> {
                        prepared = result.prepared
                        mutableState.update { it.copy(stage = RequesterFundingStage.REVIEW) }
                    }

                    is FundingReconciliation.WalletOutcomeUnknown -> {
                        mutableState.update { it.copy(
                            stage = RequesterFundingStage.WALLET_OUTCOME_UNKNOWN,
                            notice = "No transaction signature was received. Check the funding status; retry in the wallet if it remains pending after 30 seconds.",
                        ) }
                    }

                    FundingReconciliation.None -> {
                        mutableState.update {
                            it.copy(
                                stage = RequesterFundingStage.SETUP,
                                notice = "No active funding operation remains. Review the current state before trying again.",
                            )
                        }
                    }
                }
            } catch (error: TimeoutCancellationException) {
                mutableState.update { it.copy(stage = RequesterFundingStage.WALLET_OUTCOME_UNKNOWN,
                    notice = "Wallet or funding check timed out. Check the saved operation before retrying.") }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(
                        stage = RequesterFundingStage.REVIEW,
                        notice = error.userMessage(),
                    )
                }
            }
        }
    }

    fun checkConfirmation() {
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    stage = RequesterFundingStage.CONFIRMING,
                    notice = "Checking existing funding state…",
                )
            }

            try {
                withTimeout(OPERATION_WAIT_MS) { applyRecovery(
                    expectedStateId = mutableState.value.stateId,
                    detail = null,
                    recovery = repository.recoverLatest(),
                ) }
            } catch (error: TimeoutCancellationException) {
                mutableState.update { it.copy(stage = RequesterFundingStage.WALLET_OUTCOME_UNKNOWN,
                    notice = "Funding check timed out. You can try checking again.") }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(
                        stage = RequesterFundingStage.CONFIRMING,
                        notice = error.userMessage(),
                    )
                }
            }
        }
    }

    private suspend fun pollConfirmation() {
        repeat(3) {
            delay(1_500L)
            val recovery = try { withTimeout(OPERATION_WAIT_MS) { repository.recoverLatest() } }
                catch (error: TimeoutCancellationException) { return }
            when (recovery) {
                is FundingReconciliation.Available -> {
                    prepared = null
                    mutableState.update {
                        it.copy(
                            stage = RequesterFundingStage.COMPLETE,
                            refreshId = recovery.refresh.refreshId,
                            notice = null,
                        )
                    }
                    return
                }

                is FundingReconciliation.ReadyForWallet -> {
                    prepared = recovery.prepared
                    mutableState.update {
                        it.copy(
                            stage = RequesterFundingStage.REVIEW,
                            notice = "Funding was not submitted. Review before opening the wallet again.",
                        )
                    }
                    return
                }

                is FundingReconciliation.Confirming,
                is FundingReconciliation.WalletOutcomeUnknown,
                FundingReconciliation.None -> Unit
            }
        }
    }

    private fun applyRecovery(
        expectedStateId: String?,
        detail: StateDetail?,
        recovery: FundingReconciliation,
    ) {
        when (recovery) {
            is FundingReconciliation.Available -> {
                if (expectedStateId == null || recovery.refresh.stateId == expectedStateId) {
                    prepared = null
                    mutableState.update {
                        it.copy(
                            stage = RequesterFundingStage.COMPLETE,
                            refreshId = recovery.refresh.refreshId,
                            notice = "Existing funding was confirmed.",
                        )
                    }
                } else {
                    mutableState.update { it.copy(stage = RequesterFundingStage.SETUP) }
                }
            }

            is FundingReconciliation.ReadyForWallet -> {
                if (expectedStateId == null || recovery.prepared.refresh.stateId == expectedStateId) {
                    prepared = recovery.prepared
                    mutableState.update {
                        it.copy(
                            stage = RequesterFundingStage.REVIEW,
                            amountInput = formatAtomicInput(
                                recovery.prepared.refresh.fundingTargetAtomic,
                                rewardConfig.decimals,
                            ),
                            walletAddress = recovery.prepared.wallet.address,
                            refreshId = recovery.prepared.refresh.refreshId,
                            operationId = recovery.prepared.operation.operationId,
                            expiresAt = recovery.prepared.refresh.refreshExpiresAt,
                            notice = "Recovered an unfinished funding operation.",
                        )
                    }
                } else {
                    mutableState.update { it.copy(stage = RequesterFundingStage.SETUP) }
                }
            }

            is FundingReconciliation.Confirming -> {
                val activeRefreshId = detail?.activeRefresh?.refreshId
                if (activeRefreshId == null || activeRefreshId == recovery.operation.entityId) {
                    mutableState.update {
                        it.copy(
                            stage = RequesterFundingStage.CONFIRMING,
                            refreshId = recovery.operation.entityId,
                            operationId = recovery.operation.operationId,
                            notice = "Checking an existing submitted funding operation.",
                        )
                    }
                } else {
                    mutableState.update { it.copy(stage = RequesterFundingStage.SETUP) }
                }
            }

            is FundingReconciliation.WalletOutcomeUnknown -> {
                mutableState.update { it.copy(
                    stage = RequesterFundingStage.WALLET_OUTCOME_UNKNOWN,
                    refreshId = recovery.operation.entityId,
                    operationId = recovery.operation.operationId,
                    notice = "Wallet result is unclear; no transaction signature was received. Check status again after 30 seconds.",
                ) }
            }

            FundingReconciliation.None -> {
                mutableState.update { it.copy(stage = RequesterFundingStage.SETUP) }
            }
        }
    }

    private companion object {
        const val OPERATION_WAIT_MS = 30_000L
    }
}

internal fun parseRewardInput(
    input: String,
    decimals: Int,
): String? {
    if (decimals !in 0..18) return null
    val value = runCatching { BigDecimal(input.trim()) }.getOrNull() ?: return null
    if (value <= BigDecimal.ZERO) return null

    return runCatching {
        value.setScale(decimals, RoundingMode.UNNECESSARY)
            .movePointRight(decimals)
            .toBigIntegerExact()
            .toString()
    }.getOrNull()
}

internal fun formatAtomicInput(
    atomic: String,
    decimals: Int,
): String =
    runCatching {
        BigDecimal(atomic)
            .movePointLeft(decimals)
            .stripTrailingZeros()
            .toPlainString()
    }.getOrDefault(atomic)

private fun Throwable.userMessage(): String =
    when (this) {
        is RequesterFundingFailure -> message ?: "Funding could not continue."
        else -> message ?: "Funding could not continue."
    }
