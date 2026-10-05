package com.sagarsystemslab.nownetwork.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class NearbyStatesDto(
    val items: List<StateSummaryDto>,
    @SerialName("next_cursor")
    val nextCursor: String? = null,
    val counts: com.sagarsystemslab.nownetwork.model.NearbyStateCounts? = null,
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
    val location: StateLocationDto? = null,
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
    val center: com.sagarsystemslab.nownetwork.model.GeoCenter? = null,
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
    val total: Int? = null,
    val categories: List<String> = emptyList(),
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
    val distanceM: Double? = null,
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
    val center: com.sagarsystemslab.nownetwork.model.GeoCenter? = null,
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
    @SerialName("video_required")
    val videoRequired: Boolean = false,
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


@Serializable
data class ClaimPrepareRequest(
    @SerialName("wallet_binding_id")
    val walletBindingId: String,
)

@Serializable
data class ClaimObserveRequest(
    val signature: String,
)

@Serializable
data class ClaimStatusDto(
    @SerialName("acceptance_id")
    val acceptanceId: String,
    @SerialName("refresh_id")
    val refreshId: String,
    val status: String,
    @SerialName("claim_slot")
    val claimSlot: Int? = null,
    @SerialName("claim_duration_seconds")
    val claimDurationSeconds: Long? = null,
    @SerialName("claim_deadline")
    val claimDeadline: String? = null,
    @SerialName("chain_signature")
    val chainSignature: String? = null,
    @SerialName("chain_status")
    val chainStatus: String? = null,
    @SerialName("refresh_status")
    val refreshStatus: String,
    @SerialName("refresh_expires_at")
    val refreshExpiresAt: String,
    @SerialName("evidence_deadline")
    val evidenceDeadline: String,
    val revision: Long,
    @SerialName("next_step")
    val nextStep: String? = null,
)

@Serializable
data class ClaimIntentDto(
    @SerialName("acceptance_id")
    val acceptanceId: String,
    @SerialName("refresh_id")
    val refreshId: String,
    val status: String,
    @SerialName("claim_slot")
    val claimSlot: Int? = null,
    @SerialName("claim_duration_seconds")
    val claimDurationSeconds: Long,
    @SerialName("claim_deadline")
    val claimDeadline: String? = null,
    @SerialName("chain_signature")
    val chainSignature: String? = null,
    @SerialName("chain_status")
    val chainStatus: String? = null,
    @SerialName("refresh_status")
    val refreshStatus: String,
    @SerialName("refresh_expires_at")
    val refreshExpiresAt: String,
    @SerialName("evidence_deadline")
    val evidenceDeadline: String,
    val revision: Long,
    @SerialName("next_step")
    val nextStep: String? = null,
    val cluster: String,
    @SerialName("program_id")
    val programId: String,
    @SerialName("wallet_address")
    val walletAddress: String,
    @SerialName("reward_mint")
    val rewardMint: String,
    @SerialName("chain_refresh_id_hex")
    val chainRefreshIdHex: String,
    val accounts: ClaimAccountsDto,
    val instruction: ClaimInstructionDto,
)

@Serializable
data class ClaimAccountsDto(
    val claimant: String,
    val config: String,
    val refresh: String,
    @SerialName("reward_mint")
    val rewardMint: String,
    @SerialName("claimant_reward_token_account")
    val claimantRewardTokenAccount: String,
)

@Serializable
data class ClaimInstructionDto(
    val name: String,
    @SerialName("refresh_id_hex")
    val refreshIdHex: String,
    @SerialName("claim_duration_seconds")
    val claimDurationSeconds: Long,
)


@Serializable
data class EvidenceChallengeDto(
    @SerialName("challenge_id")
    val challengeId: String,
    @SerialName("refresh_id")
    val refreshId: String,
    @SerialName("acceptance_id")
    val acceptanceId: String,
    val nonce: String,
    @SerialName("issued_at")
    val issuedAt: String,
    @SerialName("expires_at")
    val expiresAt: String,
    @SerialName("policy_version")
    val policyVersion: Int,
    val capture: EvidenceCapturePolicyDto,
    @SerialName("claim_status")
    val claimStatus: String,
    @SerialName("claim_revision")
    val claimRevision: Long,
    @SerialName("refresh_status")
    val refreshStatus: String,
    @SerialName("refresh_revision")
    val refreshRevision: Long,
)

@Serializable
data class EvidenceCapturePolicyDto(
    @SerialName("media_required")
    val mediaRequired: Boolean,
    @SerialName("location_required")
    val locationRequired: Boolean,
    @SerialName("video_required")
    val videoRequired: Boolean = false,
)

@Serializable
data class EvidenceUploadAuthorizeRequest(
    val nonce: String,
    @SerialName("media_mime")
    val mediaMime: String,
)

@Serializable
data class EvidenceUploadAuthorizationDto(
    @SerialName("evidence_id")
    val evidenceId: String,
    @SerialName("challenge_id")
    val challengeId: String,
    @SerialName("object_key")
    val objectKey: String,
    @SerialName("media_mime")
    val mediaMime: String,
    val upload: EvidenceUploadTargetDto,
    @SerialName("application_deadline")
    val applicationDeadline: String,
    val replayed: Boolean,
    @SerialName("video_upload")
    val videoUpload: EvidenceUploadTargetDto? = null,
)

@Serializable
data class EvidenceUploadTargetDto(
    val method: String,
    @SerialName("signed_url")
    val signedUrl: String,
    @SerialName("content_type")
    val contentType: String,
)

@Serializable
data class EvidenceLocationSampleDto(
    val lat: Double,
    val lng: Double,
    @SerialName("accuracy_m")
    val accuracyM: Double? = null,
    val provider: String? = null,
    @SerialName("mock_signal")
    val mockSignal: Boolean? = null,
    @SerialName("captured_offset_ms")
    val capturedOffsetMs: Long? = null,
)

@Serializable
data class EvidenceCommitRequest(
    val nonce: String,
    @SerialName("media_sha256")
    val mediaSha256: String,
    @SerialName("media_size_bytes")
    val mediaSizeBytes: Long,
    @SerialName("answer_value")
    val answerValue: JsonElement,
    @SerialName("capture_started_monotonic_ms")
    val captureStartedMonotonicMs: Long,
    @SerialName("capture_completed_monotonic_ms")
    val captureCompletedMonotonicMs: Long,
    @SerialName("location_samples")
    val locationSamples: List<EvidenceLocationSampleDto> = emptyList(),
    val video: EvidenceVideoDto? = null,
)

@Serializable
data class EvidenceVideoDto(
    val sha256: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("duration_ms") val durationMs: Long,
    @SerialName("capture_started_monotonic_ms") val captureStartedMonotonicMs: Long,
    @SerialName("capture_completed_monotonic_ms") val captureCompletedMonotonicMs: Long,
)

@Serializable
data class EvidenceCommitDto(
    @SerialName("evidence_id")
    val evidenceId: String,
    @SerialName("refresh_id")
    val refreshId: String,
    @SerialName("acceptance_id")
    val acceptanceId: String,
    @SerialName("challenge_id")
    val challengeId: String,
    @SerialName("evidence_status")
    val evidenceStatus: String,
    @SerialName("claim_status")
    val claimStatus: String,
    @SerialName("refresh_status")
    val refreshStatus: String,
    @SerialName("committed_at")
    val committedAt: String,
    val media: EvidenceCommittedMediaDto,
    val replayed: Boolean,
    @SerialName("next_step")
    val nextStep: String,
)

@Serializable
data class EvidenceCommittedMediaDto(
    @SerialName("object_key")
    val objectKey: String,
    val sha256: String,
    @SerialName("size_bytes")
    val sizeBytes: Long,
    val mime: String,
)


@Serializable
data class VerificationResultDto(
    @SerialName("verification_result_id")
    val verificationResultId: String,
    @SerialName("refresh_id")
    val refreshId: String,
    val result: String,
    val status: String,
    @SerialName("reason_codes")
    val reasonCodes: List<String> = emptyList(),
    @SerialName("evidence_ids")
    val evidenceIds: List<String> = emptyList(),
    @SerialName("final_answer")
    val finalAnswer: JsonElement? = null,
    @SerialName("evidence_set_revision")
    val evidenceSetRevision: Int,
    @SerialName("policy_version")
    val policyVersion: Int,
    @SerialName("canonical_digest")
    val canonicalDigest: String? = null,
    @SerialName("completed_at")
    val completedAt: String? = null,
    @SerialName("refresh_status")
    val refreshStatus: String? = null,
    @SerialName("refresh_revision")
    val refreshRevision: Long? = null,
    val replayed: Boolean,
    @SerialName("next_step")
    val nextStep: String,
    @SerialName("state_projection")
    val stateProjection: VerificationStateProjectionDto? = null,
)

@Serializable
data class VerificationStateProjectionDto(
    @SerialName("state_id")
    val stateId: String,
    @SerialName("refresh_id")
    val refreshId: String,
    @SerialName("verification_result_id")
    val verificationResultId: String,
    @SerialName("state_revision")
    val stateRevision: Long,
    @SerialName("history_id")
    val historyId: String,
    @SerialName("observed_at")
    val observedAt: String,
    @SerialName("aging_at")
    val agingAt: String,
    @SerialName("fresh_until")
    val freshUntil: String,
    @SerialName("current_value")
    val currentValue: JsonElement,
    val projected: Boolean,
    val replayed: Boolean,
    val superseded: Boolean,
    val freshness: String? = null,
)

@Serializable
data class PaymentStatusDto(
    @SerialName("refresh_id")
    val refreshId: String,
    @SerialName("verification_result_id")
    val verificationResultId: String,
    @SerialName("settlement_id")
    val settlementId: String? = null,
    @SerialName("settlement_status")
    val settlementStatus: String,
    @SerialName("payment_status")
    val paymentStatus: String,
    @SerialName("chain_signature")
    val chainSignature: String? = null,
    @SerialName("chain_commitment")
    val chainCommitment: String? = null,
    @SerialName("confirmed_at")
    val confirmedAt: String? = null,
    @SerialName("finalized_at")
    val finalizedAt: String? = null,
    @SerialName("updated_at")
    val updatedAt: String,
)

@Serializable
data class ReceiptDto(
    @SerialName("receipt_id")
    val receiptId: String,
    @SerialName("refresh_id")
    val refreshId: String,
    @SerialName("state_id")
    val stateId: String,
    @SerialName("verification_result_id")
    val verificationResultId: String,
    @SerialName("settlement_id")
    val settlementId: String,
    val status: String,
    @SerialName("final_value")
    val finalValue: JsonElement,
    @SerialName("observed_at")
    val observedAt: String,
    @SerialName("verification_class")
    val verificationClass: String,
    @SerialName("reward_amount_atomic")
    val rewardAmountAtomic: String,
    @SerialName("reward_mint")
    val rewardMint: String,
    @SerialName("verification_digest")
    val verificationDigest: String,
    @SerialName("settlement_operation_hash")
    val settlementOperationHash: String,
    @SerialName("receipt_digest")
    val receiptDigest: String,
    @SerialName("settlement_signature")
    val settlementSignature: String,
    @SerialName("chain_commitment")
    val chainCommitment: String,
    @SerialName("finalized_at")
    val finalizedAt: String,
    val revision: Long,
)
