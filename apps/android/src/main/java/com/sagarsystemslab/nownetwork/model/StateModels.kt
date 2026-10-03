package com.sagarsystemslab.nownetwork.model

import kotlinx.serialization.Serializable

@Serializable
data class GeoCenter(val latitude: Double, val longitude: Double) {
    val valid: Boolean get() = latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
}

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
    val location: StateLocation? = null,
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

@Serializable
data class StateLocation(
    val locationId: String,
    val name: String,
    val locationType: String,
    val displayAddress: String?,
    val center: GeoCenter? = null,
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
