package com.sagarsystemslab.nownetwork.repository

import com.sagarsystemslab.nownetwork.data.local.ActiveOperationDao
import com.sagarsystemslab.nownetwork.data.local.ActiveOperationEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityRepositoryTest {
    @Test
    fun unresolvedOperationsArePinnedAboveCompletedHistory() = runBlocking {
        val dao = FakeActiveOperationDao(
            listOf(
                operation(
                    id = "completed",
                    state = "PAID",
                    updatedAt = 3_000L,
                ),
                operation(
                    id = "active",
                    state = "VERIFYING",
                    updatedAt = 2_000L,
                ),
                operation(
                    id = "failed-needs-recovery",
                    state = "FAILED",
                    updatedAt = 4_000L,
                ),
            ),
        )
        val repository = DefaultActivityRepository(dao)

        val items = repository.observeActivity().first()

        assertEquals(
            listOf("failed-needs-recovery", "active", "completed"),
            items.map { it.operationId },
        )
        assertTrue(items[0].active)
        assertTrue(items[1].active)
        assertEquals(false, items[2].active)
    }

    @Test
    fun paymentPendingStaysActiveAndPaidMovesToCompleted() = runBlocking {
        val dao = FakeActiveOperationDao(
            listOf(
                operation(
                    id = "pending-payment",
                    state = "PENDING",
                    updatedAt = 2_000L,
                ),
                operation(
                    id = "paid-payment",
                    state = "PAID",
                    updatedAt = 3_000L,
                ),
            ),
        )
        val repository = DefaultActivityRepository(dao)

        val items = repository.observeActivity().first()
        val pending = items.first { it.operationId == "pending-payment" }
        val paid = items.first { it.operationId == "paid-payment" }

        assertTrue(pending.active)
        assertEquals(false, paid.active)
    }

    private fun operation(
        id: String,
        state: String,
        updatedAt: Long,
    ) = ActiveOperationEntity(
        operationId = id,
        type = "SETTLEMENT",
        entityId = "refresh-$id",
        localState = state,
        remoteState = null,
        chainSignature = null,
        lastValidBlockHeight = null,
        idempotencyKey = "key-$id",
        createdAtMs = 1_000L,
        updatedAtMs = updatedAt,
    )
}

private class FakeActiveOperationDao(
    initial: List<ActiveOperationEntity>,
) : ActiveOperationDao {
    private val rows = MutableStateFlow(initial)

    override suspend fun get(operationId: String): ActiveOperationEntity? =
        rows.value.firstOrNull { it.operationId == operationId }

    override suspend fun getByIdempotencyKey(
        idempotencyKey: String,
    ): ActiveOperationEntity? =
        rows.value.firstOrNull { it.idempotencyKey == idempotencyKey }

    override suspend fun listAll(): List<ActiveOperationEntity> = rows.value

    override fun observeAll(): Flow<List<ActiveOperationEntity>> = rows

    override suspend fun upsert(entity: ActiveOperationEntity) {
        rows.value = rows.value.filterNot { it.operationId == entity.operationId } + entity
    }

    override suspend fun delete(operationId: String) {
        rows.value = rows.value.filterNot { it.operationId == operationId }
    }
}
