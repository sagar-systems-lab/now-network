package com.sagarsystemslab.nownetwork.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "cached_states")
data class CachedStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "state_id")
    val stateId: String,
    val revision: Long,
    @ColumnInfo(name = "payload_json")
    val payloadJson: String,
    @ColumnInfo(name = "observed_at_ms")
    val observedAtMs: Long,
    @ColumnInfo(name = "aging_at_ms")
    val agingAtMs: Long,
    @ColumnInfo(name = "fresh_until_ms")
    val freshUntilMs: Long,
    @ColumnInfo(name = "cached_at_ms")
    val cachedAtMs: Long,
)

@Entity(
    tableName = "cached_opportunities",
    indices = [
        Index(value = ["state_id"]),
        Index(value = ["status", "refresh_expires_at_ms"]),
    ],
)
data class CachedOpportunityEntity(
    @PrimaryKey
    @ColumnInfo(name = "refresh_id")
    val refreshId: String,
    @ColumnInfo(name = "state_id")
    val stateId: String,
    val revision: Long,
    val status: String,
    @ColumnInfo(name = "reward_amount_atomic")
    val rewardAmountAtomic: String,
    @ColumnInfo(name = "reward_mint")
    val rewardMint: String,
    @ColumnInfo(name = "claim_deadline_ms")
    val claimDeadlineMs: Long?,
    @ColumnInfo(name = "evidence_deadline_ms")
    val evidenceDeadlineMs: Long?,
    @ColumnInfo(name = "refresh_expires_at_ms")
    val refreshExpiresAtMs: Long,
    @ColumnInfo(name = "cached_at_ms")
    val cachedAtMs: Long,
)

@Entity(
    tableName = "active_operations",
    indices = [
        Index(value = ["entity_id"]),
        Index(value = ["idempotency_key"], unique = true),
        Index(value = ["updated_at_ms"]),
    ],
)
data class ActiveOperationEntity(
    @PrimaryKey
    @ColumnInfo(name = "operation_id")
    val operationId: String,
    val type: String,
    @ColumnInfo(name = "entity_id")
    val entityId: String,
    @ColumnInfo(name = "local_state")
    val localState: String,
    @ColumnInfo(name = "remote_state")
    val remoteState: String?,
    @ColumnInfo(name = "chain_signature")
    val chainSignature: String?,
    @ColumnInfo(name = "last_valid_block_height")
    val lastValidBlockHeight: Long?,
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
)

@Entity(
    tableName = "pending_evidence",
    indices = [
        Index(value = ["acceptance_id"]),
        Index(value = ["challenge_id"], unique = true),
        Index(value = ["upload_state", "updated_at_ms"]),
    ],
)
data class PendingEvidenceEntity(
    @PrimaryKey
    @ColumnInfo(name = "evidence_id")
    val evidenceId: String,
    @ColumnInfo(name = "acceptance_id")
    val acceptanceId: String,
    @ColumnInfo(name = "challenge_id")
    val challengeId: String,
    @ColumnInfo(name = "local_file_path")
    val localFilePath: String,
    @ColumnInfo(name = "sha256_hex")
    val sha256Hex: String?,
    @ColumnInfo(name = "media_size_bytes")
    val mediaSizeBytes: Long?,
    @ColumnInfo(name = "captured_at_ms")
    val capturedAtMs: Long,
    @ColumnInfo(name = "capture_elapsed_realtime_ms")
    val captureElapsedRealtimeMs: Long,
    @ColumnInfo(name = "upload_state")
    val uploadState: PendingEvidenceStatus,
    @ColumnInfo(name = "last_error")
    val lastError: String?,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
)

@Entity(
    tableName = "outbox",
    indices = [
        Index(value = ["idempotency_key"], unique = true),
        Index(value = ["entity_id", "status"]),
        Index(value = ["status", "next_attempt_at_ms"]),
        Index(value = ["dependency_operation_id"]),
    ],
)
data class OutboxEntity(
    @PrimaryKey
    @ColumnInfo(name = "operation_id")
    val operationId: String,
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,
    @ColumnInfo(name = "operation_type")
    val operationType: String,
    @ColumnInfo(name = "entity_id")
    val entityId: String,
    @ColumnInfo(name = "payload_digest")
    val payloadDigest: String,
    @ColumnInfo(name = "payload_reference")
    val payloadReference: String?,
    @ColumnInfo(name = "dependency_operation_id")
    val dependencyOperationId: String?,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int,
    @ColumnInfo(name = "next_attempt_at_ms")
    val nextAttemptAtMs: Long?,
    val status: LocalOutboxStatus,
    @ColumnInfo(name = "last_error")
    val lastError: String?,
    @ColumnInfo(name = "requires_connectivity")
    val requiresConnectivity: Boolean,
    @ColumnInfo(name = "requires_user_presence")
    val requiresUserPresence: Boolean,
)

@Entity(tableName = "wallet_session_metadata")
data class WalletSessionMetadataEntity(
    @PrimaryKey
    @ColumnInfo(name = "profile_key")
    val profileKey: String,
    @ColumnInfo(name = "wallet_address")
    val walletAddress: String?,
    @ColumnInfo(name = "solana_cluster")
    val solanaCluster: String,
    @ColumnInfo(name = "auth_subject_id")
    val authSubjectId: String?,
    @ColumnInfo(name = "session_state")
    val sessionState: String,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
)

@Entity(tableName = "sync_metadata")
data class SyncMetadataEntity(
    @PrimaryKey
    @ColumnInfo(name = "stream_key")
    val streamKey: String,
    @ColumnInfo(name = "latest_revision")
    val latestRevision: Long,
    @ColumnInfo(name = "snapshot_required")
    val snapshotRequired: Boolean,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
)
