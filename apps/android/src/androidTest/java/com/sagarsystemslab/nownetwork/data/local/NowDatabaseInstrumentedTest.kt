package com.sagarsystemslab.nownetwork.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NowDatabaseInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: NowDatabase
    private lateinit var store: LocalPersistenceStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, NowDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = LocalPersistenceStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun revisionGapRequiresSnapshotBeforeAdvancingCache() = runBlocking {
        val revisionOne = state(revision = 1, payload = """{"spaces":4}""")
        val revisionThree = state(revision = 3, payload = """{"spaces":2}""")

        assertEquals(CacheApplyResult.INSERTED, store.applyStateDelta(revisionOne))
        assertEquals(CacheApplyResult.SNAPSHOT_REQUIRED, store.applyStateDelta(revisionThree))
        assertEquals(1L, database.cachedStateDao().get("parking-a")?.revision)

        val metadataAfterGap = database.syncMetadataDao().get("state:parking-a")
        assertNotNull(metadataAfterGap)
        assertTrue(metadataAfterGap!!.snapshotRequired)
        assertEquals(1L, metadataAfterGap.latestRevision)

        assertEquals(CacheApplyResult.UPDATED, store.applyStateSnapshot(revisionThree))
        assertEquals(3L, database.cachedStateDao().get("parking-a")?.revision)
        assertFalse(database.syncMetadataDao().get("state:parking-a")!!.snapshotRequired)
    }

    @Test
    fun sameRevisionWithDifferentPayloadRequiresReconciliation() = runBlocking {
        val initial = state(revision = 7, payload = """{"spaces":4}""")
        val conflicting = state(revision = 7, payload = """{"spaces":1}""")

        store.applyStateDelta(initial)

        assertEquals(
            CacheApplyResult.REVISION_CONFLICT,
            store.applyStateDelta(conflicting),
        )
        assertEquals("""{"spaces":4}""", database.cachedStateDao().get("parking-a")?.payloadJson)
        assertTrue(database.syncMetadataDao().get("state:parking-a")!!.snapshotRequired)
    }

    @Test
    fun evidenceAndOutboxAreAtomicAndIdempotent() = runBlocking {
        val evidence = pendingEvidence("evidence-a")
        val item = outbox("operation-a", "idempotency-a", "evidence-a", "digest-a")

        val first = store.saveEvidenceAndEnqueue(evidence, item)
        val replay = store.saveEvidenceAndEnqueue(evidence, item.copy(operationId = "operation-b"))

        assertEquals(first.operationId, replay.operationId)
        assertNotNull(database.pendingEvidenceDao().get("evidence-a"))
        assertEquals("operation-a", database.outboxDao().getByIdempotencyKey("idempotency-a")?.operationId)
    }

    @Test
    fun idempotencyCollisionRollsBackEvidenceMutation() = runBlocking {
        store.enqueueOutbox(
            outbox(
                operationId = "existing-operation",
                idempotencyKey = "shared-key",
                entityId = "existing-evidence",
                digest = "digest-a",
            ),
        )

        val conflicting = outbox(
            operationId = "new-operation",
            idempotencyKey = "shared-key",
            entityId = "new-evidence",
            digest = "digest-b",
        )

        assertThrows(IdempotencyCollisionException::class.java) {
            runBlocking {
                store.saveEvidenceAndEnqueue(
                    pendingEvidence("new-evidence"),
                    conflicting,
                )
            }
        }

        assertNull(database.pendingEvidenceDao().get("new-evidence"))
    }

    @Test
    fun missingEvidenceFileFailsClosed() = runBlocking {
        val evidence = pendingEvidence("evidence-missing")
        database.pendingEvidenceDao().upsert(evidence)

        store.markEvidenceFileMissing(
            evidenceId = evidence.evidenceId,
            reason = "LOCAL_FILE_MISSING",
            updatedAtMs = 9_000,
        )

        val stored = database.pendingEvidenceDao().get(evidence.evidenceId)
        assertEquals(PendingEvidenceStatus.FAILED, stored?.uploadState)
        assertEquals("LOCAL_FILE_MISSING", stored?.lastError)
    }

    @Test
    fun activeOperationSurvivesDatabaseReopen() {
        runBlocking {
            val name = "now-recovery-" + UUID.randomUUID() + ".db"
            context.deleteDatabase(name)

            val operation = ActiveOperationEntity(
                operationId = "operation-recovery",
                type = "SETTLEMENT",
                entityId = "refresh-a",
                localState = "VERIFYING",
                remoteState = null,
                chainSignature = "signature-a",
                lastValidBlockHeight = 1234,
                idempotencyKey = "settlement-refresh-a",
                createdAtMs = 1_000,
                updatedAtMs = 2_000,
            )

            val first = Room.databaseBuilder(context, NowDatabase::class.java, name).build()
            first.activeOperationDao().upsert(operation)
            first.close()

            val reopened = Room.databaseBuilder(context, NowDatabase::class.java, name).build()
            val restored = LocalPersistenceStore(reopened).restoreActiveOperations()

            assertEquals(listOf(operation), restored)

            reopened.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun initialSchemaContainsOperationalTables() {
        val expected = setOf(
            "cached_states",
            "cached_opportunities",
            "active_operations",
            "pending_evidence",
            "outbox",
            "wallet_session_metadata",
            "sync_metadata",
        )

        val cursor = database.openHelper.readableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'table'",
        )

        val actual = buildSet {
            cursor.use {
                while (it.moveToNext()) {
                    add(it.getString(0))
                }
            }
        }

        assertTrue(actual.containsAll(expected))
    }

    private fun state(revision: Long, payload: String) = CachedStateEntity(
        stateId = "parking-a",
        revision = revision,
        payloadJson = payload,
        observedAtMs = 1_000,
        agingAtMs = 2_000,
        freshUntilMs = 3_000,
        cachedAtMs = 4_000 + revision,
    )

    private fun pendingEvidence(evidenceId: String) = PendingEvidenceEntity(
        evidenceId = evidenceId,
        acceptanceId = "acceptance-a",
        challengeId = "challenge-" + evidenceId,
        localFilePath = "/data/user/0/app/files/" + evidenceId + ".jpg",
        sha256Hex = "abc123",
        mediaSizeBytes = 1024,
        capturedAtMs = 2_000,
        captureElapsedRealtimeMs = 500,
        uploadState = PendingEvidenceStatus.UPLOAD_READY,
        lastError = null,
        createdAtMs = 2_000,
        updatedAtMs = 2_000,
    )

    private fun outbox(
        operationId: String,
        idempotencyKey: String,
        entityId: String,
        digest: String,
    ) = OutboxEntity(
        operationId = operationId,
        idempotencyKey = idempotencyKey,
        operationType = "EVIDENCE_COMMIT",
        entityId = entityId,
        payloadDigest = digest,
        payloadReference = "evidence:" + entityId,
        dependencyOperationId = null,
        createdAtMs = 2_000,
        attemptCount = 0,
        nextAttemptAtMs = null,
        status = LocalOutboxStatus.PENDING,
        lastError = null,
        requiresConnectivity = true,
        requiresUserPresence = false,
    )
}
