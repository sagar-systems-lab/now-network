package com.sagarsystemslab.nownetwork.repository

import com.sagarsystemslab.nownetwork.auth.AppSession
import com.sagarsystemslab.nownetwork.auth.AuthGateway
import com.sagarsystemslab.nownetwork.auth.UnavailableAuthGateway
import com.sagarsystemslab.nownetwork.config.PublicRuntimeConfig
import com.sagarsystemslab.nownetwork.data.local.WalletSessionMetadataDao
import com.sagarsystemslab.nownetwork.data.local.WalletSessionMetadataEntity
import com.sagarsystemslab.nownetwork.network.ApiFailure
import com.sagarsystemslab.nownetwork.network.MeDto
import com.sagarsystemslab.nownetwork.network.NearbyOpportunitiesDto
import com.sagarsystemslab.nownetwork.network.NearbyOpportunityQuery
import com.sagarsystemslab.nownetwork.network.NearbyStateQuery
import com.sagarsystemslab.nownetwork.network.NearbyStatesDto
import com.sagarsystemslab.nownetwork.network.NowApiClient
import com.sagarsystemslab.nownetwork.network.StateDetailDto
import com.sagarsystemslab.nownetwork.network.WalletBindingDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRepositoryTest {
    @Test
    fun expiredInitialTokenRefreshesOnceAndPersistsMetadata() = runBlocking {
        val auth = FakeAuthGateway()
        val api = FakeNowApiClient()
        val dao = FakeWalletSessionMetadataDao()
        val repository = SessionRepository(
            auth = auth,
            api = api,
            metadataDao = dao,
            config = config(),
            serverClock = ServerClock(),
        )

        val result = repository.bootstrap()

        assertTrue(result is SessionBootstrapState.Ready)
        assertEquals(1, auth.refreshCalls)
        assertEquals(listOf("initial-token", "refreshed-token"), api.meTokens)
        assertEquals("auth-subject", dao.saved?.authSubjectId)
        assertEquals("wallet-address", dao.saved?.walletAddress)
        assertEquals("devnet", dao.saved?.solanaCluster)
        assertEquals("AUTHENTICATED", dao.saved?.sessionState)
    }

    @Test
    fun missingAuthConfigurationProducesControlledUnavailableState() = runBlocking {
        val repository = SessionRepository(
            auth = UnavailableAuthGateway(),
            api = FakeNowApiClient(),
            metadataDao = FakeWalletSessionMetadataDao(),
            config = config(),
            serverClock = ServerClock(),
        )

        val result = repository.bootstrap()

        assertEquals(
            SessionBootstrapState.Unavailable(
                SessionBootstrapState.Unavailable.Reason.CONFIGURATION,
            ),
            result,
        )
    }

    private fun config() =
        PublicRuntimeConfig(
            apiBaseUrl = "https://now.example",
            supabaseUrl = "",
            supabasePublishableKey = "",
            solanaCluster = "devnet",
        )
}

private class FakeAuthGateway : AuthGateway {
    var refreshCalls = 0

    override suspend fun ensureAnonymousSession(): AppSession =
        AppSession(
            authSubjectId = "auth-subject",
            accessToken = "initial-token",
        )

    override suspend fun currentSession(): AppSession? = null

    override suspend fun refreshIfNeeded(): AppSession {
        refreshCalls += 1
        return AppSession(
            authSubjectId = "auth-subject",
            accessToken = "refreshed-token",
        )
    }

    override suspend fun signOutLocal() = Unit
}

private class FakeNowApiClient : NowApiClient {
    val meTokens = mutableListOf<String>()

    override suspend fun nearbyStates(query: NearbyStateQuery): NearbyStatesDto =
        error("not used")

    override suspend fun stateDetail(stateId: String): StateDetailDto =
        error("not used")

    override suspend fun nearbyOpportunities(
        query: NearbyOpportunityQuery,
        accessToken: String,
    ): NearbyOpportunitiesDto =
        error("not used")

    override suspend fun me(accessToken: String): MeDto {
        meTokens += accessToken
        if (accessToken == "initial-token") {
            throw ApiFailure.AuthExpired("expired")
        }

        return MeDto(
            actorId = "33333333-3333-4333-8333-333333333333",
            status = "ACTIVE",
            walletBindings = listOf(
                WalletBindingDto(
                    walletBindingId = "44444444-4444-4444-8444-444444444444",
                    walletAddress = "wallet-address",
                    cluster = "devnet",
                    status = "ACTIVE",
                    revision = 2,
                ),
            ),
        )
    }
}

private class FakeWalletSessionMetadataDao : WalletSessionMetadataDao {
    var saved: WalletSessionMetadataEntity? = null

    override suspend fun get(profileKey: String): WalletSessionMetadataEntity? = saved

    override suspend fun upsert(entity: WalletSessionMetadataEntity) {
        saved = entity
    }

    override suspend fun delete(profileKey: String) {
        saved = null
    }
}
