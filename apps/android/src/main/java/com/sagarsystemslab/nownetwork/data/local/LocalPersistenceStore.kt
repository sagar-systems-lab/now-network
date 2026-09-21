package com.sagarsystemslab.nownetwork.data.local

import androidx.room.withTransaction

class LocalPersistenceStore(
    private val database: NowDatabase,
) {
    suspend fun applyStateDelta(incoming: CachedStateEntity): CacheApplyResult =
        database.withTransaction {
            validateState(incoming)

            val stateDao = database.cachedStateDao()
            val syncDao = database.syncMetadataDao()
            val current = stateDao.get(incoming.stateId)
            val streamKey = stateStreamKey(incoming.stateId)
            val existingSync = syncDao.get(streamKey)

            when (decideRevision(current?.revision, incoming.revision)) {
                RevisionDecision.INSERT -> {
                    stateDao.upsert(incoming)
                    syncDao.upsert(
                        SyncMetadataEntity(
                            streamKey = streamKey,
                            latestRevision = incoming.revision,
                            snapshotRequired = false,
                            updatedAtMs = incoming.cachedAtMs,
                        ),
                    )
                    CacheApplyResult.INSERTED
                }

                RevisionDecision.APPLY_NEXT -> {
                    stateDao.upsert(incoming)
                    syncDao.upsert(
                        SyncMetadataEntity(
                            streamKey = streamKey,
                            latestRevision = incoming.revision,
                            snapshotRequired = existingSync?.snapshotRequired ?: false,
                            updatedAtMs = incoming.cachedAtMs,
                        ),
                    )
                    CacheApplyResult.UPDATED
                }

                RevisionDecision.IGNORE_STALE -> CacheApplyResult.IGNORED_STALE

                RevisionDecision.IGNORE_DUPLICATE -> {
                    checkNotNull(current)

                    if (current.sameAuthoritativeState(incoming)) {
                        CacheApplyResult.IGNORED_DUPLICATE
                    } else {
                        syncDao.upsert(
                            SyncMetadataEntity(
                                streamKey = streamKey,
                                latestRevision = current.revision,
                                snapshotRequired = true,
                                updatedAtMs = incoming.cachedAtMs,
                            ),
                        )
                        CacheApplyResult.REVISION_CONFLICT
                    }
                }

                RevisionDecision.REQUIRE_SNAPSHOT -> {
                    checkNotNull(current)
                    syncDao.upsert(
                        SyncMetadataEntity(
                            streamKey = streamKey,
                            latestRevision = current.revision,
                            snapshotRequired = true,
                            updatedAtMs = incoming.cachedAtMs,
                        ),
                    )
                    CacheApplyResult.SNAPSHOT_REQUIRED
                }
            }
        }

    suspend fun applyStateSnapshot(snapshot: CachedStateEntity): CacheApplyResult =
        database.withTransaction {
            validateState(snapshot)

            val stateDao = database.cachedStateDao()
            val syncDao = database.syncMetadataDao()
            val current = stateDao.get(snapshot.stateId)

            if (current != null && snapshot.revision < current.revision) {
                return@withTransaction CacheApplyResult.IGNORED_STALE
            }

            stateDao.upsert(snapshot)
            syncDao.upsert(
                SyncMetadataEntity(
                    streamKey = stateStreamKey(snapshot.stateId),
                    latestRevision = snapshot.revision,
                    snapshotRequired = false,
                    updatedAtMs = snapshot.cachedAtMs,
                ),
            )

            if (current == null) CacheApplyResult.INSERTED else CacheApplyResult.UPDATED
        }

    suspend fun saveEvidenceAndEnqueue(
        evidence: PendingEvidenceEntity,
        outboxItem: OutboxEntity,
    ): OutboxEntity =
        database.withTransaction {
            require(evidence.evidenceId == outboxItem.entityId) {
                "evidence outbox entity must match evidence id"
            }

            database.pendingEvidenceDao().upsert(evidence)
            enqueueOutboxLocked(outboxItem)
        }

    suspend fun enqueueOutbox(item: OutboxEntity): OutboxEntity =
        database.withTransaction {
            enqueueOutboxLocked(item)
        }

    suspend fun markEvidenceFileMissing(
        evidenceId: String,
        reason: String,
        updatedAtMs: Long,
    ) {
        val changed = database.pendingEvidenceDao().markFailed(
            evidenceId = evidenceId,
            status = PendingEvidenceStatus.FAILED,
            reason = reason,
            updatedAtMs = updatedAtMs,
        )

        check(changed == 1) { "pending evidence record does not exist" }
    }

    suspend fun restoreActiveOperations(): List<ActiveOperationEntity> =
        database.activeOperationDao().listAll()

    private suspend fun enqueueOutboxLocked(item: OutboxEntity): OutboxEntity {
        require(item.attemptCount >= 0) { "attempt count must be non-negative" }

        val outboxDao = database.outboxDao()
        val existing = outboxDao.getByIdempotencyKey(item.idempotencyKey)

        if (existing == null) {
            outboxDao.insert(item)
            return item
        }

        if (!existing.sameSemanticOperation(item)) {
            throw IdempotencyCollisionException(item.idempotencyKey)
        }

        return existing
    }

    private fun validateState(state: CachedStateEntity) {
        require(state.revision >= 0) { "state revision must be non-negative" }
        require(state.observedAtMs <= state.agingAtMs) {
            "aging time cannot precede observation"
        }
        require(state.agingAtMs <= state.freshUntilMs) {
            "freshness deadline cannot precede aging"
        }
    }

    private fun stateStreamKey(stateId: String): String = "state:$stateId"
}

private fun CachedStateEntity.sameAuthoritativeState(other: CachedStateEntity): Boolean =
    stateId == other.stateId &&
        revision == other.revision &&
        payloadJson == other.payloadJson &&
        observedAtMs == other.observedAtMs &&
        agingAtMs == other.agingAtMs &&
        freshUntilMs == other.freshUntilMs

private fun OutboxEntity.sameSemanticOperation(other: OutboxEntity): Boolean =
    idempotencyKey == other.idempotencyKey &&
        operationType == other.operationType &&
        entityId == other.entityId &&
        payloadDigest == other.payloadDigest &&
        payloadReference == other.payloadReference &&
        dependencyOperationId == other.dependencyOperationId &&
        requiresConnectivity == other.requiresConnectivity &&
        requiresUserPresence == other.requiresUserPresence
