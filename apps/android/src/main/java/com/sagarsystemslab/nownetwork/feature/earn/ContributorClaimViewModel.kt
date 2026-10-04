package com.sagarsystemslab.nownetwork.feature.earn

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sagarsystemslab.nownetwork.config.RewardDisplayConfig
import com.sagarsystemslab.nownetwork.network.ClaimStatusDto
import com.sagarsystemslab.nownetwork.network.OpportunityDto
import com.sagarsystemslab.nownetwork.repository.ClaimReconciliation
import com.sagarsystemslab.nownetwork.repository.ContributorClaimFailure
import com.sagarsystemslab.nownetwork.repository.ContributorClaimRepository
import com.sagarsystemslab.nownetwork.repository.PreparedContributorClaim
import com.sagarsystemslab.nownetwork.wallet.WalletInteractionHost
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ContributorClaimStage {
    LOADING,
    REVIEW,
    PREPARING,
    READY_FOR_WALLET,
    SUBMITTING,
    CONFIRMING,
    CLAIMED,
    EVIDENCE_COMMITTED,
    ERROR,
}

data class ContributorClaimUiState(
    val refreshId: String? = null,
    val opportunity: OpportunityDto? = null,
    val stage: ContributorClaimStage = ContributorClaimStage.LOADING,
    val walletAddress: String? = null,
    val claimDurationSeconds: Long? = null,
    val claim: ClaimStatusDto? = null,
    val message: String? = null,
    val canPrepare: Boolean = false,
)

@HiltViewModel
class ContributorClaimViewModel @Inject constructor(
    private val repository: ContributorClaimRepository,
    private val rewardConfig: RewardDisplayConfig,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ContributorClaimUiState())
    val state: StateFlow<ContributorClaimUiState> = mutableState.asStateFlow()

    private var prepared: PreparedContributorClaim? = null
    private var openJob: kotlinx.coroutines.Job? = null

    fun open(refreshId: String) {
        if (mutableState.value.refreshId == refreshId && mutableState.value.opportunity != null) {
            return
        }

        prepared = null
        mutableState.value = ContributorClaimUiState(
            refreshId = refreshId,
            stage = ContributorClaimStage.LOADING,
        )

        openJob?.cancel()
        openJob = viewModelScope.launch {
            try {
                val entry = recoverClaimEntry(repository, refreshId)
                if (mutableState.value.refreshId != refreshId) return@launch
                if (entry.recovery != ClaimReconciliation.None) {
                    applyReconciliation(entry.recovery)
                } else {
                    val opportunity = requireNotNull(entry.opportunity)
                    mutableState.update { it.copy(
                        opportunity = opportunity,
                        stage = if (opportunity.availability.claimable) ContributorClaimStage.REVIEW else ContributorClaimStage.ERROR,
                        message = if (opportunity.availability.claimable) null else "This opportunity is no longer available.",
                        canPrepare = opportunity.availability.claimable,
                    ) }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (mutableState.value.refreshId == refreshId) showError(error)
            }
        }
    }

    fun prepare(host: WalletInteractionHost) {
        val refreshId = mutableState.value.refreshId ?: return
        if (mutableState.value.stage !in setOf(
                ContributorClaimStage.REVIEW,
                ContributorClaimStage.ERROR,
            )
        ) {
            return
        }

        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    stage = ContributorClaimStage.PREPARING,
                    message = null,
                    canPrepare = false,
                )
            }

            try {
                val result = repository.prepareNew(host, refreshId)
                prepared = result
                mutableState.update {
                    it.copy(
                        stage = ContributorClaimStage.READY_FOR_WALLET,
                        walletAddress = result.wallet.address,
                        claimDurationSeconds = result.intent.claimDurationSeconds,
                        message = null,
                        canPrepare = false,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showError(error)
            }
        }
    }

    fun submit(host: WalletInteractionHost) {
        val current = prepared ?: return
        if (mutableState.value.stage != ContributorClaimStage.READY_FOR_WALLET) return

        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    stage = ContributorClaimStage.SUBMITTING,
                    message = null,
                )
            }

            try {
                applyReconciliation(repository.submit(host, current))
            } catch (error: CancellationException) {
                throw error
            } catch (error: ContributorClaimFailure.WalletRejected) {
                mutableState.update {
                    it.copy(
                        stage = ContributorClaimStage.READY_FOR_WALLET,
                        message = error.message,
                    )
                }
            } catch (error: Exception) {
                showError(error)
            }
        }
    }

    fun checkConfirmation() {
        val refreshId = mutableState.value.refreshId ?: return
        if (mutableState.value.stage !in setOf(
                ContributorClaimStage.CONFIRMING,
                ContributorClaimStage.ERROR,
            )
        ) {
            return
        }

        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    stage = ContributorClaimStage.CONFIRMING,
                    message = "Checking authoritative claim state…",
                )
            }
            try {
                applyReconciliation(repository.recover(refreshId))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showError(error)
            }
        }
    }

    fun rewardText(): String {
        val opportunity = mutableState.value.opportunity ?: return ""
        return formatReward(
            atomic = opportunity.reward.poolAtomic,
            mint = opportunity.reward.mint,
            config = rewardConfig,
        )
    }

    fun estimatedRewardText(): String? {
        val opportunity = mutableState.value.opportunity ?: return null
        val atomic = com.sagarsystemslab.nownetwork.model.estimatedPayoutAtomic(
            opportunity.reward.poolAtomic, opportunity.reward.payoutRule,
            opportunity.evidenceSummary.requiredWitnesses,
        ) ?: return null
        return formatReward(atomic, opportunity.reward.mint, rewardConfig)
    }

    private fun applyReconciliation(result: ClaimReconciliation) {
        when (result) {
            is ClaimReconciliation.EvidenceCommitted -> {
                prepared = null
                mutableState.update { it.copy(stage = ContributorClaimStage.EVIDENCE_COMMITTED, claim = result.claim, canPrepare = false, message = null) }
            }

            is ClaimReconciliation.Claimed -> {
                prepared = null
                mutableState.update {
                    it.copy(
                        stage = ContributorClaimStage.CLAIMED,
                        claim = result.claim,
                        message = null,
                    )
                }
            }

            is ClaimReconciliation.Confirming -> {
                mutableState.update {
                    it.copy(
                        stage = ContributorClaimStage.CONFIRMING,
                        message = "Transaction submitted. Waiting for authoritative confirmation.",
                    )
                }
            }

            is ClaimReconciliation.ReadyForWallet -> {
                prepared = result.prepared
                mutableState.update {
                    it.copy(
                        stage = ContributorClaimStage.READY_FOR_WALLET,
                        walletAddress = result.prepared.wallet.address,
                        claimDurationSeconds = result.prepared.intent.claimDurationSeconds,
                        message = null,
                    )
                }
            }

            ClaimReconciliation.None -> {
                mutableState.update {
                    it.copy(
                        stage = ContributorClaimStage.ERROR,
                        message = "No active claim transaction was found. You can prepare the claim again.",
                        canPrepare = it.opportunity?.availability?.claimable == true,
                    )
                }
            }
        }
    }

    private fun showError(error: Exception) {
        val failedStage = mutableState.value.stage
        val canPrepareAgain = failedStage == ContributorClaimStage.PREPARING &&
            error is ContributorClaimFailure &&
            (
                error is ContributorClaimFailure.WalletUnavailable ||
                    error is ContributorClaimFailure.WalletRejected ||
                    error is ContributorClaimFailure.WalletBusy ||
                    error is ContributorClaimFailure.Wallet
            )

        val message = when (error) {
            is ContributorClaimFailure.Unavailable,
            is ContributorClaimFailure.Expired,
            is ContributorClaimFailure.Rejected,
            is ContributorClaimFailure.WalletUnavailable,
            is ContributorClaimFailure.WalletRejected,
            is ContributorClaimFailure.WalletBusy,
            is ContributorClaimFailure.WalletMismatch,
            is ContributorClaimFailure.Wallet,
            is ContributorClaimFailure.Configuration,
            is ContributorClaimFailure.Network,
            is ContributorClaimFailure.Server,
            is ContributorClaimFailure.Protocol -> error.message
            else -> "Claim could not be prepared safely."
        }

        mutableState.update {
            it.copy(
                stage = ContributorClaimStage.ERROR,
                message = message,
                canPrepare = canPrepareAgain,
            )
        }
    }
}
