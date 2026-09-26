package com.sagarsystemslab.nownetwork.network

sealed class ApiFailure(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class Configuration(message: String) : ApiFailure(message)

    class NetworkUnavailable(cause: Throwable) :
        ApiFailure("Network is unavailable", cause)

    class Timeout(cause: Throwable) :
        ApiFailure("Network request timed out", cause)

    class RateLimited(
        val retryAfterMs: Long?,
        message: String,
    ) : ApiFailure(message)

    class AuthExpired(message: String) : ApiFailure(message)

    class ServerFailure(
        val statusCode: Int,
        message: String,
    ) : ApiFailure(message)

    class BusinessError(
        val statusCode: Int,
        val code: String,
        val safeToRetry: Boolean,
        val retryAfterMs: Long?,
        message: String,
    ) : ApiFailure(message)

    class ProtocolError(message: String, cause: Throwable? = null) :
        ApiFailure(message, cause)
}
