package com.sagarsystemslab.nownetwork.network

import com.sagarsystemslab.nownetwork.config.PublicRuntimeConfig
import com.sagarsystemslab.nownetwork.repository.ServerClock
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

@Singleton
class KtorNowApiClient @Inject constructor(
    private val client: HttpClient,
    private val json: Json,
    private val config: PublicRuntimeConfig,
    private val serverClock: ServerClock,
) : NowApiClient {
    override suspend fun nearbyStates(query: NearbyStateQuery): NearbyStatesDto =
        get(
            path = "/v1/states/nearby",
            deserializer = NearbyStatesDto.serializer(),
        ) {
            parameter("lat", query.latitude)
            parameter("lng", query.longitude)
            parameter("radius_m", query.radiusMeters)
            parameter("limit", query.limit)
            query.cursor?.let { parameter("cursor", it) }
        }

    override suspend fun stateDetail(stateId: String): StateDetailDto =
        get(
            path = "/v1/states/$stateId",
            deserializer = StateDetailDto.serializer(),
        )

    override suspend fun me(accessToken: String): MeDto =
        get(
            path = "/v1/me",
            accessToken = accessToken,
            deserializer = MeDto.serializer(),
        )

    private suspend fun <T> get(
        path: String,
        accessToken: String? = null,
        deserializer: DeserializationStrategy<T>,
        configure: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ): T {
        var lastRetryable: ApiFailure? = null

        repeat(MAX_SAFE_GET_ATTEMPTS) { attempt ->
            try {
                return getOnce(
                    path = path,
                    accessToken = accessToken,
                    deserializer = deserializer,
                    configure = configure,
                )
            } catch (error: ApiFailure) {
                val lastAttempt = attempt == MAX_SAFE_GET_ATTEMPTS - 1
                if (!error.isRetryableReadFailure() || lastAttempt) {
                    throw error
                }
                lastRetryable = error
            }
        }

        throw checkNotNull(lastRetryable)
    }

    private suspend fun <T> getOnce(
        path: String,
        accessToken: String?,
        deserializer: DeserializationStrategy<T>,
        configure: io.ktor.client.request.HttpRequestBuilder.() -> Unit,
    ): T {
        val baseUrl = config.apiBaseUrl.trim().trimEnd('/')
        if (baseUrl.isEmpty()) {
            throw ApiFailure.Configuration("NOW API base URL is not configured")
        }

        val response = try {
            client.get("$baseUrl$path") {
                header("x-request-id", UUID.randomUUID().toString())
                if (accessToken != null) {
                    bearerAuth(accessToken)
                }
                configure()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: HttpRequestTimeoutException) {
            throw ApiFailure.Timeout(error)
        } catch (error: SocketTimeoutException) {
            throw ApiFailure.Timeout(error)
        } catch (error: IOException) {
            throw ApiFailure.NetworkUnavailable(error)
        }

        return decodeResponse(response, deserializer)
    }

    private fun ApiFailure.isRetryableReadFailure(): Boolean =
        this is ApiFailure.NetworkUnavailable ||
            this is ApiFailure.Timeout ||
            this is ApiFailure.ServerFailure

    private suspend fun <T> decodeResponse(
        response: HttpResponse,
        deserializer: DeserializationStrategy<T>,
    ): T {
        val raw = response.bodyAsText()
        val root = try {
            json.parseToJsonElement(raw).jsonObject
        } catch (error: Exception) {
            throw ApiFailure.ProtocolError("NOW API returned malformed JSON", error)
        }

        root["server_time"]?.jsonPrimitive?.contentOrNull?.let { serverTime ->
            try {
                serverClock.update(serverTime)
            } catch (error: Exception) {
                throw ApiFailure.ProtocolError("NOW API returned invalid server_time", error)
            }
        } ?: throw ApiFailure.ProtocolError("NOW API response is missing server_time")

        if (response.status.isSuccess()) {
            val data = root["data"]
                ?: throw ApiFailure.ProtocolError("NOW API response is missing data")
            return try {
                json.decodeFromJsonElement(deserializer, data)
            } catch (error: Exception) {
                throw ApiFailure.ProtocolError("NOW API data contract mismatch", error)
            }
        }

        val errorObject = root["error"]?.jsonObject
            ?: throw ApiFailure.ProtocolError("NOW API error response is missing error")

        val code = errorObject["code"]?.jsonPrimitive?.contentOrNull ?: "UNKNOWN"
        val message = errorObject["message"]?.jsonPrimitive?.contentOrNull
            ?: "NOW API request failed"
        val safeToRetry = errorObject["safe_to_retry"]?.jsonPrimitive?.booleanOrNull ?: false
        val retryAfterMs = errorObject["retry_after_ms"]?.jsonPrimitive?.longOrNull
        val statusCode = response.status.value

        throw when (statusCode) {
            401 -> ApiFailure.AuthExpired(message)
            429 -> ApiFailure.RateLimited(retryAfterMs, message)
            in 500..599 -> ApiFailure.ServerFailure(statusCode, message)
            else -> ApiFailure.BusinessError(
                statusCode = statusCode,
                code = code,
                safeToRetry = safeToRetry,
                retryAfterMs = retryAfterMs,
                message = message,
            )
        }
    }

    private companion object {
        const val MAX_SAFE_GET_ATTEMPTS = 2
    }
}
