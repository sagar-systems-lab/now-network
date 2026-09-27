package com.sagarsystemslab.nownetwork.repository

import com.sagarsystemslab.nownetwork.auth.AppSession
import com.sagarsystemslab.nownetwork.auth.AuthGateway
import com.sagarsystemslab.nownetwork.data.local.CachedOpportunityDao
import com.sagarsystemslab.nownetwork.data.local.CachedOpportunityEntity
import com.sagarsystemslab.nownetwork.model.NearbyStatePage
import com.sagarsystemslab.nownetwork.model.StateDetail
import com.sagarsystemslab.nownetwork.model.StateSummary
import com.sagarsystemslab.nownetwork.network.ApiFailure
import com.sagarsystemslab.nownetwork.network.MeDto
import com.sagarsystemslab.nownetwork.network.NearbyOpportunitiesDto
import com.sagarsystemslab.nownetwork.network.NearbyOpportunityQuery
import com.sagarsystemslab.nownetwork.network.NearbyStateQuery
import com.sagarsystemslab.nownetwork.network.NearbyStatesDto
import com.sagarsystemslab.nownetwork.network.NowApiClient
import com.sagarsystemslab.nownetwork.network.OpportunityAvailabilityDto
import com.sagarsystemslab.nownetwork.network.OpportunityDto
import com.sagarsystemslab.nownetwork.network.OpportunityEvidenceSummaryDto
import com.sagarsystemslab.nownetwork.network.OpportunityLocationDto
import com.sagarsystemslab.nownetwork.network.OpportunityRewardDto
import com.sagarsystemslab.nownetwork.network.StateDetailDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpportunityRepositoryTest {
    @Test
    fun expiredTokenRefreshesOnceAndFreshOpportunityIsCached() = runBlocking {
        val auth = OpportunityAuthGateway()
        val api = OpportunityApi()
        val dao = FakeCachedOpportunityDao()
        val repository = DefaultOpportunityRepository(
            api = api,
            auth = auth,
            opportunityDao = dao,
            stateRepository = EmptyStateRepository(),
            serverClock = ServerClock(),
        )

        val page = repository.refreshNearby(
            NearbyOpportunityQuery(
                latitude = 29.4,
                longitude = 76.9,
                radiusMeters = 1_000,
            ),
        )

        assertEquals(1, auth.refreshCalls)
        assertEquals(listOf("old-token", "new-token"), api.tokens)
        assertEquals("Parking Lot B", page.items.single().title)
        assertEquals("450000", dao.saved?.rewardAmountAtomic)
        assertEquals("CLAIMABLE", dao.saved?.status)

        val cached = repository.observeCached().first()
        assertEquals("Refresh available state", cached.single().title)
        assertTrue(cached.single().cachedOnly)
    }
}

private class OpportunityAuthGateway : AuthGateway {
    var refreshCalls = 0

    override suspend fun ensureAnonymousSession(): AppSession =
        AppSession("actor-subject", "old-token")

    override suspend fun currentSession(): AppSession? = null

    override suspend fun refreshIfNeeded(): AppSession {
        refreshCalls += 1
        return AppSession("actor-subject", "new-token")
    }

    override suspend fun signOutLocal() = Unit
}

private class OpportunityApi : NowApiClient {
    val tokens = mutableListOf<String>()

    override suspend fun nearbyOpportunities(
        query: NearbyOpportunityQuery,
        accessToken: String,
    ): NearbyOpportunitiesDto {
        tokens += accessToken
        if (accessToken == "old-token") {
            throw ApiFailure.AuthExpired("expired")
        }

        return NearbyOpportunitiesDto(
            items = listOf(
                OpportunityDto(
                    refreshId = "22222222-2222-4222-8222-222222222222",
                    stateId = "33333333-3333-4333-8333-333333333333",
                    stateVersion = 1,
                    title = "Parking Lot B",
                    question = "Available spaces",
                    stateType = "NUMERIC",
                    unitCode = "spaces",
                    location = OpportunityLocationDto(
                        locationId = "44444444-4444-4444-8444-444444444444",
                        name = "Parking Lot B",
                        locationType = "PARKING",
                        displayAddress = "Demo district",
                    ),
                    reward = OpportunityRewardDto(
                        mint = "mint-a",
                        poolAtomic = "450000",
                        payoutRule = "EQUAL",
                    ),
                    distanceM = 92.0,
                    expiresAt = "2035-01-01T00:10:00Z",
                    evidenceDeadline = "2035-01-01T00:08:00Z",
                    verificationClass = "FAST",
                    evidenceSummary = OpportunityEvidenceSummaryDto(
                        templateKey = "parking.photo.v1",
                        mediaRequired = true,
                        locationRequired = true,
                        requiredWitnesses = 1,
                        maxWitnesses = 1,
                    ),
                    availability = OpportunityAvailabilityDto(
                        claimable = true,
                        activeClaims = 0,
                        remainingSlots = 1,
                    ),
                    stateRevision = 4,
                    revision = 3,
                ),
            ),
            nextCursor = null,
        )
    }

    override suspend fun nearbyStates(query: NearbyStateQuery): NearbyStatesDto =
        error("not used")

    override suspend fun stateDetail(stateId: String): StateDetailDto =
        error("not used")

    override suspend fun me(accessToken: String): MeDto =
        error("not used")
}

private class FakeCachedOpportunityDao : CachedOpportunityDao {
    private val rows = MutableStateFlow<List<CachedOpportunityEntity>>(emptyList())
    var saved: CachedOpportunityEntity? = null

    override suspend fun get(refreshId: String): CachedOpportunityEntity? =
        rows.value.firstOrNull { it.refreshId == refreshId }

    override fun observeAll(): Flow<List<CachedOpportunityEntity>> = rows

    override fun observeActive(nowMs: Long): Flow<List<CachedOpportunityEntity>> =
        flowOf(rows.value.filter { it.refreshExpiresAtMs > nowMs })

    override suspend fun upsert(entity: CachedOpportunityEntity) {
        saved = entity
        rows.value = rows.value.filterNot { it.refreshId == entity.refreshId } + entity
    }
}

private class EmptyStateRepository : StateRepository {
    override fun observeCachedStates(): Flow<List<StateSummary>> = flowOf(emptyList())

    override suspend fun refreshNearby(query: NearbyStateQuery): NearbyStatePage =
        error("not used")

    override suspend fun getState(stateId: String): StateDetail =
        error("not used")

    override suspend fun reconcileRealtimeState(
        stateId: String,
        incomingRevision: Long?,
    ): StateDetail? =
        error("not used")
}
