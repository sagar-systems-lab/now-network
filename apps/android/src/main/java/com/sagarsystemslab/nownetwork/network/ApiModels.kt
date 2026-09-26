package com.sagarsystemslab.nownetwork.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class NearbyStatesDto(
    val items: List<StateSummaryDto>,
    @SerialName("next_cursor")
    val nextCursor: String? = null,
)

@Serializable
data class StateSummaryDto(
    @SerialName("state_id")
    val stateId: String,
    val title: String,
    val question: String,
    @SerialName("state_type")
    val stateType: String,
    val value: JsonElement? = null,
    @SerialName("unit_code")
    val unitCode: String? = null,
    @SerialName("freshness_status")
    val freshnessStatus: String? = null,
    @SerialName("observed_at")
    val observedAt: String? = null,
    @SerialName("aging_at")
    val agingAt: String? = null,
    @SerialName("fresh_until")
    val freshUntil: String? = null,
    @SerialName("verification_class")
    val verificationClass: String? = null,
    @SerialName("refresh_status")
    val refreshStatus: String? = null,
    @SerialName("conflict_active")
    val conflictActive: Boolean = false,
    @SerialName("distance_m")
    val distanceM: Double,
    val revision: Long,
)

@Serializable
data class StateDetailDto(
    @SerialName("state_id")
    val stateId: String,
    val version: Int,
    @SerialName("canonical_key")
    val canonicalKey: String,
    val title: String,
    val question: String,
    @SerialName("state_type")
    val stateType: String,
    val value: JsonElement? = null,
    @SerialName("unit_code")
    val unitCode: String? = null,
    @SerialName("freshness_status")
    val freshnessStatus: String? = null,
    @SerialName("observed_at")
    val observedAt: String? = null,
    @SerialName("observation_earliest")
    val observationEarliest: String? = null,
    @SerialName("observation_latest")
    val observationLatest: String? = null,
    @SerialName("aging_at")
    val agingAt: String? = null,
    @SerialName("fresh_until")
    val freshUntil: String? = null,
    @SerialName("verification_class")
    val verificationClass: String? = null,
    @SerialName("conflict_active")
    val conflictActive: Boolean = false,
    val revision: Long,
    val location: StateLocationDto,
    val verification: StateVerificationDto? = null,
    @SerialName("active_refresh")
    val activeRefresh: ActiveRefreshDto? = null,
)

@Serializable
data class StateLocationDto(
    @SerialName("location_id")
    val locationId: String,
    val name: String,
    @SerialName("location_type")
    val locationType: String,
    @SerialName("display_address")
    val displayAddress: String? = null,
)

@Serializable
data class StateVerificationDto(
    val status: String,
    @SerialName("reason_codes")
    val reasonCodes: List<String> = emptyList(),
    @SerialName("evidence_count")
    val evidenceCount: Int,
)

@Serializable
data class ActiveRefreshDto(
    @SerialName("refresh_id")
    val refreshId: String,
    val status: String,
    @SerialName("verification_class")
    val verificationClass: String,
    @SerialName("expires_at")
    val expiresAt: String,
    val revision: Long,
)

@Serializable
data class MeDto(
    @SerialName("actor_id")
    val actorId: String,
    val status: String,
    @SerialName("wallet_bindings")
    val walletBindings: List<WalletBindingDto> = emptyList(),
)

@Serializable
data class WalletBindingDto(
    @SerialName("wallet_binding_id")
    val walletBindingId: String,
    @SerialName("wallet_address")
    val walletAddress: String,
    val cluster: String,
    val status: String,
    val revision: Long,
)


@Serializable
data class NearbyOpportunitiesDto(
    val items: List<OpportunityDto>,
    @SerialName("next_cursor")
    val nextCursor: String? = null,
)

@Serializable
data class OpportunityDto(
    @SerialName("refresh_id")
    val refreshId: String,
    @SerialName("state_id")
    val stateId: String,
    @SerialName("state_version")
    val stateVersion: Int,
    val title: String,
    val question: String,
    @SerialName("state_type")
    val stateType: String,
    @SerialName("unit_code")
    val unitCode: String? = null,
    val location: OpportunityLocationDto,
    val reward: OpportunityRewardDto,
    @SerialName("distance_m")
    val distanceM: Double,
    @SerialName("expires_at")
    val expiresAt: String,
    @SerialName("evidence_deadline")
    val evidenceDeadline: String,
    @SerialName("verification_class")
    val verificationClass: String,
    @SerialName("evidence_summary")
    val evidenceSummary: OpportunityEvidenceSummaryDto,
    val availability: OpportunityAvailabilityDto,
    @SerialName("state_revision")
    val stateRevision: Long,
    val revision: Long,
)

@Serializable
data class OpportunityLocationDto(
    @SerialName("location_id")
    val locationId: String,
    val name: String,
    @SerialName("location_type")
    val locationType: String,
    @SerialName("display_address")
    val displayAddress: String? = null,
)

@Serializable
data class OpportunityRewardDto(
    val mint: String,
    @SerialName("pool_atomic")
    val poolAtomic: String,
    @SerialName("payout_rule")
    val payoutRule: String,
)

@Serializable
data class OpportunityEvidenceSummaryDto(
    @SerialName("template_key")
    val templateKey: String,
    @SerialName("media_required")
    val mediaRequired: Boolean,
    @SerialName("location_required")
    val locationRequired: Boolean,
    @SerialName("required_witnesses")
    val requiredWitnesses: Int,
    @SerialName("max_witnesses")
    val maxWitnesses: Int,
)

@Serializable
data class OpportunityAvailabilityDto(
    val claimable: Boolean,
    @SerialName("active_claims")
    val activeClaims: Int,
    @SerialName("remaining_slots")
    val remainingSlots: Int,
)
