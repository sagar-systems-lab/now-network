package com.sagarsystemslab.nownetwork.model

import kotlinx.serialization.Serializable

@Serializable
data class StateSummary(
    val stateId: String,
    val title: String,
    val question: String,
    val stateType: String,
    val valueJson: String?,
    val unitCode: String?,
    val freshnessStatus: String?,
    val observedAtMillis: Long?,
    val agingAtMillis: Long?,
    val freshUntilMillis: Long?,
    val verificationClass: String?,
    val refreshStatus: String?,
    val conflictActive: Boolean,
    val distanceMeters: Double?,
    val revision: Long,
)

data class StateDetail(
    val stateId: String,
    val version: Int,
    val canonicalKey: String,
    val title: String,
    val question: String,
    val stateType: String,
    val valueJson: String?,
    val unitCode: String?,
    val freshnessStatus: String?,
    val observedAtMillis: Long?,
    val observationEarliestMillis: Long?,
    val observationLatestMillis: Long?,
    val agingAtMillis: Long?,
    val freshUntilMillis: Long?,
    val verificationClass: String?,
    val conflictActive: Boolean,
    val revision: Long,
    val location: StateLocation,
    val verification: StateVerification?,
    val activeRefresh: ActiveRefresh?,
)

data class StateLocation(
    val locationId: String,
    val name: String,
    val locationType: String,
    val displayAddress: String?,
)

data class StateVerification(
    val status: String,
    val reasonCodes: List<String>,
    val evidenceCount: Int,
)

data class ActiveRefresh(
    val refreshId: String,
    val status: String,
    val verificationClass: String,
    val expiresAtMillis: Long,
    val revision: Long,
)

data class NearbyStatePage(
    val items: List<StateSummary>,
    val nextCursor: String?,
)
