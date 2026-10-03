package com.sagarsystemslab.nownetwork.feature.earn

import com.sagarsystemslab.nownetwork.network.OpportunityDto
import com.sagarsystemslab.nownetwork.repository.ClaimReconciliation
import com.sagarsystemslab.nownetwork.repository.ContributorClaimRepository

internal data class ClaimEntry(val recovery: ClaimReconciliation, val opportunity: OpportunityDto? = null)

/** Existing claims are excluded from public opportunities, so reconcile before browsing. */
internal suspend fun recoverClaimEntry(repository: ContributorClaimRepository, refreshId: String): ClaimEntry {
    val recovered = repository.recover(refreshId)
    return if (recovered == ClaimReconciliation.None) ClaimEntry(recovered, repository.loadOpportunity(refreshId))
        else ClaimEntry(recovered)
}
