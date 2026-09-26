package com.sagarsystemslab.nownetwork.repository

import com.sagarsystemslab.nownetwork.data.local.CacheApplyResult
import com.sagarsystemslab.nownetwork.data.local.CachedStateEntity
import com.sagarsystemslab.nownetwork.network.MeDto
import com.sagarsystemslab.nownetwork.network.NearbyStateQuery
import com.sagarsystemslab.nownetwork.network.NearbyStatesDto
import com.sagarsystemslab.nownetwork.network.NowApiClient
import com.sagarsystemslab.nownetwork.network.StateDetailDto
import com.sagarsystemslab.nownetwork.network.StateLocationDto
import com.sagarsystemslab.nownetwork.network.StateSummaryDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class StateRepositoryTest {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    @Test
    fun nearbySnapshotMapsAndPersistsThroughRevisionAwareCache() = runBlocking {
        val cache = FakeStateCache()
        val repository = DefaultStateRepository(
            api = FakeStateApi(),
            stateCache = cache,
            json = json,
            serverClock = ServerClock(),
        )

        val page = repository.refreshNearby(
            NearbyStateQuery(
                latitude = 12.5,
                longitude = 77.25,
                radiusMeters = 3_000,
            ),
        )

        assertEquals("Parking Lot B", page.items.single().title)
        assertEquals(7L, page.items.single().revision)
        assertEquals("next-state", page.nextCursor)

        val persisted = requireNotNull(cache.lastApplied)
        assertEquals(7L, persisted.revision)
        assertEquals(1_790_424_000_000L, persisted.observedAtMs)
        assertEquals(1_790_424_420_000L, persisted.agingAtMs)
        assertEquals(1_790_424_600_000L, persisted.freshUntilMs)

        val observed = repository.observeCachedStates().first().single()
        assertEquals("Parking Lot B", observed.title)
        assertEquals(84.5, observed.distanceMeters)
    }
}

private class FakeStateApi : NowApiClient {
    override suspend fun nearbyStates(query: NearbyStateQuery): NearbyStatesDto =
        NearbyStatesDto(
            items = listOf(
                StateSummaryDto(
                    stateId = "22222222-2222-4222-8222-222222222222",
                    title = "Parking Lot B",
                    question = "How many spaces are available?",
                    stateType = "NUMERIC",
                    value = JsonPrimitive(3),
                    unitCode = "spaces",
                    freshnessStatus = "LIVE",
                    observedAt = "2026-09-26T12:00:00Z",
                    agingAt = "2026-09-26T12:07:00Z",
                    freshUntil = "2026-09-26T12:10:00Z",
                    verificationClass = "FAST",
                    refreshStatus = null,
                    conflictActive = false,
                    distanceM = 84.5,
                    revision = 7,
                ),
            ),
            nextCursor = "next-state",
        )

    override suspend fun stateDetail(stateId: String): StateDetailDto =
        StateDetailDto(
            stateId = stateId,
            version = 1,
            canonicalKey = "parking.available_spaces.v1",
            title = "Parking Lot B",
            question = "How many spaces are available?",
            stateType = "NUMERIC",
            value = JsonPrimitive(3),
            unitCode = "spaces",
            freshnessStatus = "LIVE",
            observedAt = "2026-09-26T12:00:00Z",
            agingAt = "2026-09-26T12:07:00Z",
            freshUntil = "2026-09-26T12:10:00Z",
            verificationClass = "FAST",
            conflictActive = false,
            revision = 7,
            location = StateLocationDto(
                locationId = "55555555-5555-4555-8555-555555555555",
                name = "Parking Lot B",
                locationType = "PARKING",
                displayAddress = "Demo Campus",
            ),
        )

    override suspend fun me(accessToken: String): MeDto =
        error("not used")
}

private class FakeStateCache : StateCache {
    private val rows = MutableStateFlow<List<CachedStateEntity>>(emptyList())
    var lastApplied: CachedStateEntity? = null

    override fun observeAll(): Flow<List<CachedStateEntity>> = rows

    override suspend fun get(stateId: String): CachedStateEntity? =
        rows.value.firstOrNull { it.stateId == stateId }

    override suspend fun applySnapshot(snapshot: CachedStateEntity): CacheApplyResult {
        lastApplied = snapshot
        rows.value = rows.value.filterNot { it.stateId == snapshot.stateId } + snapshot
        return CacheApplyResult.INSERTED
    }
}
