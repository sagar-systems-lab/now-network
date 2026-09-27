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


@Serializable
data class WalletBindingChallengeDto(
    @SerialName("challenge_id")
    val challengeId: String,
    @SerialName("wallet_address")
    val walletAddress: String,
    val cluster: String,
    val purpose: String,
    val domain: String,
    val message: String,
    @SerialName("issued_at")
    val issuedAt: String,
    @SerialName("expires_at")
    val expiresAt: String,
)

@Serializable
data class WalletBindingVerifyDto(
    @SerialName("actor_id")
    val actorId: String,
    val recovered: Boolean,
    @SerialName("wallet_binding")
    val walletBinding: WalletBindingDto,
)

@Serializable
data class RefreshDto(
    @SerialName("refresh_id")
    val refreshId: String,
    @SerialName("state_id")
    val stateId: String,
    @SerialName("state_version")
    val stateVersion: Int,
    val status: String,
    @SerialName("verification_class")
    val verificationClass: String,
    @SerialName("required_witnesses")
    val requiredWitnesses: Int,
    @SerialName("max_witnesses")
    val maxWitnesses: Int,
    @SerialName("payout_rule")
    val payoutRule: String,
    @SerialName("proof_policy")
    val proofPolicy: JsonElement,
    @SerialName("proof_policy_digest")
    val proofPolicyDigest: String,
    @SerialName("intent_core_hash")
    val intentCoreHash: String,
    @SerialName("refresh_expires_at")
    val refreshExpiresAt: String,
    @SerialName("evidence_deadline")
    val evidenceDeadline: String,
    @SerialName("reward_mint")
    val rewardMint: String,
    @SerialName("funding_target_atomic")
    val fundingTargetAtomic: String,
    @SerialName("funding_operation_id")
    val fundingOperationId: String? = null,
    @SerialName("chain_total_funded_atomic")
    val chainTotalFundedAtomic: String,
    @SerialName("chain_refresh_address")
    val chainRefreshAddress: String? = null,
    @SerialName("chain_status")
    val chainStatus: String? = null,
    @SerialName("chain_observed_at")
    val chainObservedAt: String? = null,
    val revision: Long,
    @SerialName("next_step")
    val nextStep: String? = null,
)

@Serializable
data class FundingIntentDto(
    @SerialName("operation_id")
    val operationId: String,
    @SerialName("refresh_id")
    val refreshId: String,
    val status: String,
    val cluster: String,
    @SerialName("program_id")
    val programId: String,
    @SerialName("reward_mint")
    val rewardMint: String,
    @SerialName("amount_atomic")
    val amountAtomic: String,
    @SerialName("intent_core_hash")
    val intentCoreHash: String,
    @SerialName("chain_refresh_id_hex")
    val chainRefreshIdHex: String,
    @SerialName("state_id_digest_hex")
    val stateIdDigestHex: String,
    @SerialName("refresh_expires_at")
    val refreshExpiresAtUnix: Long,
    @SerialName("verification_class")
    val verificationClass: String,
    @SerialName("required_witnesses")
    val requiredWitnesses: Int,
    @SerialName("max_witnesses")
    val maxWitnesses: Int,
    @SerialName("payout_rule")
    val payoutRule: String,
    @SerialName("creator_wallet")
    val creatorWallet: String,
    val accounts: FundingAccountsDto,
    @SerialName("instruction_plan")
    val instructionPlan: List<String>,
)

@Serializable
data class FundingAccountsDto(
    val config: String,
    val refresh: String,
    val contribution: String,
    @SerialName("source_token_account")
    val sourceTokenAccount: String,
    @SerialName("vault_token_account")
    val vaultTokenAccount: String,
    @SerialName("token_program")
    val tokenProgram: String,
    @SerialName("associated_token_program")
    val associatedTokenProgram: String,
    @SerialName("system_program")
    val systemProgram: String,
)

@Serializable
data class WalletBindingChallengeRequest(
    @SerialName("wallet_address")
    val walletAddress: String,
    val cluster: String,
)

@Serializable
data class WalletBindingVerifyRequest(
    @SerialName("challenge_id")
    val challengeId: String,
    val signature: String,
)

@Serializable
data class CreateRefreshRequest(
    @SerialName("state_id")
    val stateId: String,
    @SerialName("wallet_binding_id")
    val walletBindingId: String,
    @SerialName("funding_target_atomic")
    val fundingTargetAtomic: String,
)

@Serializable
data class FundingObserveRequest(
    val signature: String,
)
