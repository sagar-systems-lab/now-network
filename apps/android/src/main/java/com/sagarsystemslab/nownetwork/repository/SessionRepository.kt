package com.sagarsystemslab.nownetwork.repository

import com.sagarsystemslab.nownetwork.auth.AuthGateway
import com.sagarsystemslab.nownetwork.auth.AuthGatewayException
import com.sagarsystemslab.nownetwork.auth.AuthUnavailableException
import com.sagarsystemslab.nownetwork.config.PublicRuntimeConfig
import com.sagarsystemslab.nownetwork.data.local.WalletSessionMetadataDao
import com.sagarsystemslab.nownetwork.data.local.WalletSessionMetadataEntity
import com.sagarsystemslab.nownetwork.network.ApiFailure
import com.sagarsystemslab.nownetwork.network.MeDto
import com.sagarsystemslab.nownetwork.network.NowApiClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SessionBootstrapState {
    data object Idle : SessionBootstrapState
    data object Bootstrapping : SessionBootstrapState
    data class Ready(
        val actorId: String,
        val actorStatus: String,
        val authSubjectId: String,
    ) : SessionBootstrapState

    data class Unavailable(
        val reason: Reason,
    ) : SessionBootstrapState {
        enum class Reason {
            CONFIGURATION,
            NETWORK,
            AUTH,
            SERVER,
            PROTOCOL,
        }
    }
}

@Singleton
class SessionRepository @Inject constructor(
    private val auth: AuthGateway,
    private val api: NowApiClient,
    private val metadataDao: WalletSessionMetadataDao,
    private val config: PublicRuntimeConfig,
    private val serverClock: ServerClock,
) {
    private val bootstrapMutex = Mutex()
    private val mutableState = MutableStateFlow<SessionBootstrapState>(SessionBootstrapState.Idle)

    val state: StateFlow<SessionBootstrapState> = mutableState.asStateFlow()

    suspend fun bootstrap(): SessionBootstrapState = bootstrapMutex.withLock {
        val existing = mutableState.value
        if (existing is SessionBootstrapState.Ready) {
            return existing
        }

        mutableState.value = SessionBootstrapState.Bootstrapping
        val result = try {
            val session = auth.ensureAnonymousSession()
            val me = loadMeWithSingleAuthRefresh(session.accessToken)
            persistMetadata(session.authSubjectId, me)

            SessionBootstrapState.Ready(
                actorId = me.actorId,
                actorStatus = me.status,
                authSubjectId = session.authSubjectId,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            SessionBootstrapState.Unavailable(error.toReason())
        }

        mutableState.value = result
        result
    }

    private suspend fun loadMeWithSingleAuthRefresh(accessToken: String): MeDto =
        try {
            api.me(accessToken)
        } catch (error: ApiFailure.AuthExpired) {
            val refreshed = auth.refreshIfNeeded()
            api.me(refreshed.accessToken)
        }

    private suspend fun persistMetadata(
        authSubjectId: String,
        me: MeDto,
    ) {
        val activeWallet = me.walletBindings.firstOrNull { it.status == "ACTIVE" }
        metadataDao.upsert(
            WalletSessionMetadataEntity(
                profileKey = PROFILE_KEY,
                walletAddress = activeWallet?.walletAddress,
                solanaCluster = activeWallet?.cluster ?: config.solanaCluster,
                authSubjectId = authSubjectId,
                sessionState = "AUTHENTICATED",
                updatedAtMs = serverClock.nowMillis(),
            ),
        )
    }

    private fun Exception.toReason(): SessionBootstrapState.Unavailable.Reason =
        when (this) {
            is AuthUnavailableException, is AuthGatewayException.Configuration,
            is ApiFailure.Configuration ->
                SessionBootstrapState.Unavailable.Reason.CONFIGURATION

            is AuthGatewayException.Network,
            is ApiFailure.NetworkUnavailable, is ApiFailure.Timeout, is ApiFailure.RateLimited ->
                SessionBootstrapState.Unavailable.Reason.NETWORK

            is AuthGatewayException.Rejected, is ApiFailure.AuthExpired ->
                SessionBootstrapState.Unavailable.Reason.AUTH

            is ApiFailure.ServerFailure, is ApiFailure.BusinessError ->
                SessionBootstrapState.Unavailable.Reason.SERVER

            is AuthGatewayException.Protocol, is ApiFailure.ProtocolError ->
                SessionBootstrapState.Unavailable.Reason.PROTOCOL

            else ->
                SessionBootstrapState.Unavailable.Reason.PROTOCOL
        }

    private companion object {
        const val PROFILE_KEY = "default"
    }
}
