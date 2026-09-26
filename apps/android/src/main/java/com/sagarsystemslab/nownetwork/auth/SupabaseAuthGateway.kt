package com.sagarsystemslab.nownetwork.auth

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException

class SupabaseAuthGateway(
    private val client: SupabaseClient,
) : AuthGateway {
    override suspend fun ensureAnonymousSession(): AppSession =
        authCall {
            client.auth.awaitInitialization()

            client.auth.currentSessionOrNull()?.let { session ->
                return@authCall session.toAppSession()
            }

            client.auth.signInAnonymously()
            requireNotNull(client.auth.currentSessionOrNull()) {
                "Supabase anonymous sign-in completed without a session"
            }.toAppSession()
        }

    override suspend fun currentSession(): AppSession? =
        authCall {
            client.auth.awaitInitialization()
            client.auth.currentSessionOrNull()?.toAppSession()
        }

    override suspend fun refreshIfNeeded(): AppSession =
        authCall {
            client.auth.awaitInitialization()
            client.auth.refreshCurrentSession()
            requireNotNull(client.auth.currentSessionOrNull()) {
                "Supabase session refresh completed without a session"
            }.toAppSession()
        }

    override suspend fun signOutLocal() {
        authCall {
            client.auth.signOut()
        }
    }

    private suspend fun <T> authCall(block: suspend () -> T): T =
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: HttpRequestTimeoutException) {
            throw AuthGatewayException.Network(error)
        } catch (error: HttpRequestException) {
            throw AuthGatewayException.Network(error)
        } catch (error: RestException) {
            throw AuthGatewayException.Rejected(
                message = error.message ?: "Authentication request was rejected",
                cause = error,
            )
        } catch (error: AuthGatewayException) {
            throw error
        } catch (error: Exception) {
            throw AuthGatewayException.Protocol(
                message = "Authentication session contract failed",
                cause = error,
            )
        }
}

private fun UserSession.toAppSession(): AppSession =
    AppSession(
        authSubjectId = requireNotNull(user?.id) {
            "Supabase session does not contain a user identity"
        },
        accessToken = accessToken,
    )

class UnavailableAuthGateway : AuthGateway {
    private fun unavailable(): Nothing =
        throw AuthUnavailableException("Supabase public auth configuration is unavailable")

    override suspend fun ensureAnonymousSession(): AppSession = unavailable()

    override suspend fun currentSession(): AppSession? = null

    override suspend fun refreshIfNeeded(): AppSession = unavailable()

    override suspend fun signOutLocal() = Unit
}
