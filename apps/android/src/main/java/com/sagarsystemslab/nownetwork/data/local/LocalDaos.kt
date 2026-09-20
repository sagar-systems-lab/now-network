package com.sagarsystemslab.nownetwork.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CachedStateDao {
    @Query("SELECT * FROM cached_states WHERE state_id = :stateId LIMIT 1")
    suspend fun get(stateId: String): CachedStateEntity?

    @Query("SELECT * FROM cached_states ORDER BY cached_at_ms DESC")
    fun observeAll(): Flow<List<CachedStateEntity>>

    @Upsert
    suspend fun upsert(entity: CachedStateEntity)
}

@Dao
interface CachedOpportunityDao {
    @Query("SELECT * FROM cached_opportunities WHERE refresh_id = :refreshId LIMIT 1")
    suspend fun get(refreshId: String): CachedOpportunityEntity?

    @Query(
        "SELECT * FROM cached_opportunities " +
            "WHERE refresh_expires_at_ms > :nowMs ORDER BY cached_at_ms DESC",
    )
    fun observeActive(nowMs: Long): Flow<List<CachedOpportunityEntity>>

    @Upsert
    suspend fun upsert(entity: CachedOpportunityEntity)
}

@Dao
interface ActiveOperationDao {
    @Query("SELECT * FROM active_operations WHERE operation_id = :operationId LIMIT 1")
    suspend fun get(operationId: String): ActiveOperationEntity?

    @Query("SELECT * FROM active_operations WHERE idempotency_key = :idempotencyKey LIMIT 1")
    suspend fun getByIdempotencyKey(idempotencyKey: String): ActiveOperationEntity?

    @Query("SELECT * FROM active_operations ORDER BY updated_at_ms ASC")
    suspend fun listAll(): List<ActiveOperationEntity>

    @Query("SELECT * FROM active_operations ORDER BY updated_at_ms ASC")
    fun observeAll(): Flow<List<ActiveOperationEntity>>

    @Upsert
    suspend fun upsert(entity: ActiveOperationEntity)

    @Query("DELETE FROM active_operations WHERE operation_id = :operationId")
    suspend fun delete(operationId: String)
}

@Dao
interface PendingEvidenceDao {
    @Query("SELECT * FROM pending_evidence WHERE evidence_id = :evidenceId LIMIT 1")
    suspend fun get(evidenceId: String): PendingEvidenceEntity?

    @Query(
        "SELECT * FROM pending_evidence " +
            "WHERE upload_state NOT IN ('VERIFIED', 'REJECTED', 'EXPIRED', 'FAILED') " +
            "ORDER BY updated_at_ms ASC",
    )
    fun observePending(): Flow<List<PendingEvidenceEntity>>

    @Upsert
    suspend fun upsert(entity: PendingEvidenceEntity)

    @Query(
        "UPDATE pending_evidence SET upload_state = :status, last_error = :reason, " +
            "updated_at_ms = :updatedAtMs WHERE evidence_id = :evidenceId",
    )
    suspend fun markFailed(
        evidenceId: String,
        status: PendingEvidenceStatus,
        reason: String,
        updatedAtMs: Long,
    ): Int
}

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox WHERE operation_id = :operationId LIMIT 1")
    suspend fun get(operationId: String): OutboxEntity?

    @Query("SELECT * FROM outbox WHERE idempotency_key = :idempotencyKey LIMIT 1")
    suspend fun getByIdempotencyKey(idempotencyKey: String): OutboxEntity?

    @Query(
        "SELECT * FROM outbox " +
            "WHERE status NOT IN ('ACKNOWLEDGED', 'EXPIRED', 'FAILED') " +
            "ORDER BY created_at_ms ASC",
    )
    fun observeOutstanding(): Flow<List<OutboxEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: OutboxEntity)

    @Query(
        "UPDATE outbox SET status = :status, attempt_count = :attemptCount, " +
            "next_attempt_at_ms = :nextAttemptAtMs, last_error = :lastError " +
            "WHERE operation_id = :operationId",
    )
    suspend fun updateDelivery(
        operationId: String,
        status: LocalOutboxStatus,
        attemptCount: Int,
        nextAttemptAtMs: Long?,
        lastError: String?,
    ): Int
}

@Dao
interface WalletSessionMetadataDao {
    @Query("SELECT * FROM wallet_session_metadata WHERE profile_key = :profileKey LIMIT 1")
    suspend fun get(profileKey: String): WalletSessionMetadataEntity?

    @Upsert
    suspend fun upsert(entity: WalletSessionMetadataEntity)

    @Query("DELETE FROM wallet_session_metadata WHERE profile_key = :profileKey")
    suspend fun delete(profileKey: String)
}

@Dao
interface SyncMetadataDao {
    @Query("SELECT * FROM sync_metadata WHERE stream_key = :streamKey LIMIT 1")
    suspend fun get(streamKey: String): SyncMetadataEntity?

    @Upsert
    suspend fun upsert(entity: SyncMetadataEntity)
}
