package com.sagarsystemslab.nownetwork.repository

import com.sagarsystemslab.nownetwork.data.local.CacheApplyResult
import com.sagarsystemslab.nownetwork.data.local.CachedStateDao
import com.sagarsystemslab.nownetwork.data.local.CachedStateEntity
import com.sagarsystemslab.nownetwork.data.local.LocalPersistenceStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

interface StateCache {
    fun observeAll(): Flow<List<CachedStateEntity>>
    suspend fun get(stateId: String): CachedStateEntity?
    suspend fun applySnapshot(snapshot: CachedStateEntity): CacheApplyResult
}

@Singleton
class RoomStateCache @Inject constructor(
    private val stateDao: CachedStateDao,
    private val persistenceStore: LocalPersistenceStore,
) : StateCache {
    override fun observeAll(): Flow<List<CachedStateEntity>> =
        stateDao.observeAll()

    override suspend fun get(stateId: String): CachedStateEntity? =
        stateDao.get(stateId)

    override suspend fun applySnapshot(snapshot: CachedStateEntity): CacheApplyResult =
        persistenceStore.applyStateSnapshot(snapshot)
}
