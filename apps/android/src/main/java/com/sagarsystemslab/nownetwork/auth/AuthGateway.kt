package com.sagarsystemslab.nownetwork.auth

data class AppSession(
    val authSubjectId: String,
    val accessToken: String,
)

interface AuthGateway {
    suspend fun ensureAnonymousSession(): AppSession
    suspend fun currentSession(): AppSession?
    suspend fun refreshIfNeeded(): AppSession
    suspend fun signOutLocal()
}

sealed class AuthGatewayException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class Configuration(message: String) : AuthGatewayException(message)

    class Network(cause: Throwable) :
        AuthGatewayException("Authentication service is unreachable", cause)

    class Rejected(message: String, cause: Throwable? = null) :
        AuthGatewayException(message, cause)

    class Protocol(message: String, cause: Throwable? = null) :
        AuthGatewayException(message, cause)
}

class AuthUnavailableException(message: String) :
    AuthGatewayException(message)
