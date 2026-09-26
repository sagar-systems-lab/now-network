package com.sagarsystemslab.nownetwork.repository

import com.sagarsystemslab.nownetwork.data.local.ActiveOperationDao
import com.sagarsystemslab.nownetwork.data.local.ActiveOperationEntity
import com.sagarsystemslab.nownetwork.model.ActivityItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface ActivityRepository {
    fun observeActivity(): Flow<List<ActivityItem>>
}

@Singleton
class DefaultActivityRepository @Inject constructor(
    private val operationDao: ActiveOperationDao,
) : ActivityRepository {
    override fun observeActivity(): Flow<List<ActivityItem>> =
        operationDao.observeAll().map { operations ->
            operations
                .map(ActiveOperationEntity::toActivityItem)
                .sortedWith(
                    compareByDescending<ActivityItem> { it.active }
                        .thenByDescending { it.updatedAtMillis },
                )
        }
}

private fun ActiveOperationEntity.toActivityItem(): ActivityItem =
    ActivityItem(
        operationId = operationId,
        entityId = entityId,
        type = type,
        localState = localState,
        remoteState = remoteState,
        updatedAtMillis = updatedAtMs,
        active = localState.uppercase() !in TERMINAL_OPERATION_STATES,
    )

private val TERMINAL_OPERATION_STATES = setOf(
    "ACKNOWLEDGED",
    "COMPLETED",
    "PAID",
    "REFUNDED",
    "REJECTED",
    "EXPIRED",
    "CANCELLED",
)
