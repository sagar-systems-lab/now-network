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
)

data class OpportunityPage(
    val items: List<OpportunitySummary>,
    val nextCursor: String?,
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
