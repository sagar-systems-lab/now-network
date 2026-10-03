package com.sagarsystemslab.nownetwork.model

data class OpportunitySummary(
    val refreshId: String,
    val stateId: String,
    val title: String,
    val question: String,
    val locationName: String?,
    val displayAddress: String?,
    val rewardAtomic: String,
    val rewardMint: String,
    val distanceMeters: Double?,
    val expiresAtMillis: Long,
    val evidenceDeadlineMillis: Long?,
    val verificationClass: String?,
    val mediaRequired: Boolean?,
    val locationRequired: Boolean?,
    val claimable: Boolean,
    val remainingSlots: Int?,
    val revision: Long,
    val cachedOnly: Boolean,
    val center: GeoCenter? = null,
    val payoutRule: String? = null,
    val requiredWitnesses: Int? = null,
)

data class OpportunityPage(
    val items: List<OpportunitySummary>,
    val nextCursor: String?,
    val total: Int? = null,
    val categories: List<String> = emptyList(),
)

data class ActivityItem(
    val operationId: String,
    val entityId: String,
    val type: String,
    val localState: String,
    val remoteState: String?,
    val updatedAtMillis: Long,
    val active: Boolean,
)

/** Estimates never replace the finalized personal payout; unknown policy stays a pool. */
fun OpportunitySummary.estimatedPayoutAtomic(): String? {
    val pool = rewardAtomic.toBigIntegerOrNull()?.takeIf { it.signum() >= 0 } ?: return null
    return when (payoutRule) {
        "SINGLE_WINNER_ALL" -> pool.toString()
        "EQUAL_SPLIT_REQUIRED_WITNESSES" -> requiredWitnesses?.takeIf { it > 0 }?.let { pool.divide(java.math.BigInteger.valueOf(it.toLong())).toString() }
        else -> null
    }
}
