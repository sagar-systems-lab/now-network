package com.sagarsystemslab.nownetwork.network

import com.sagarsystemslab.nownetwork.config.PublicRuntimeConfig
import com.sagarsystemslab.nownetwork.repository.ServerClock
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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
) : NowApiClient, EvidenceReadApiClient, EvidenceApiClient, VerificationApiClient, PaymentApiClient, ReceiptApiClient {
    override suspend fun nearbyStates(query: NearbyStateQuery): NearbyStatesDto =
        get(
            path = "/v1/states/nearby",
            deserializer = NearbyStatesDto.serializer(),
        ) {
            parameter("lat", query.latitude)
            parameter("lng", query.longitude)
            parameter("radius_m", query.radiusMeters)
            parameter("limit", query.limit)
            parameter("q", query.search)
            parameter("freshness", query.freshness)
            query.cursor?.let { parameter("cursor", it) }
        }

    override suspend fun payoutWalletBindingId(accessToken: String): String? =
        (experienceGet("/v1/me/preferences", accessToken)["payout_wallet_binding_id"] as? kotlinx.serialization.json.JsonPrimitive)?.let { if (it is kotlinx.serialization.json.JsonNull) null else it.content }

    override suspend fun stateDetail(stateId: String): StateDetailDto =
        get(
            path = "/v1/states/$stateId",
            deserializer = StateDetailDto.serializer(),
        )

    override suspend fun nearbyOpportunities(
        query: NearbyOpportunityQuery,
        accessToken: String,
    ): NearbyOpportunitiesDto =
        get(
            path = "/v1/opportunities/nearby",
            accessToken = accessToken,
            deserializer = NearbyOpportunitiesDto.serializer(),
        ) {
            parameter("lat", query.latitude)
            parameter("lng", query.longitude)
            parameter("radius_m", query.radiusMeters)
            parameter("limit", query.limit)
            parameter("sort", query.sort)
            query.category?.let { parameter("category", it) }
            query.cursor?.let { parameter("cursor", it) }
        }

    override suspend fun opportunityDetail(
        refreshId: String,
        accessToken: String,
    ): OpportunityDto =
        get(
            path = "/v1/opportunities/$refreshId",
            accessToken = accessToken,
            deserializer = OpportunityDto.serializer(),
        )

    override suspend fun prepareClaim(
        refreshId: String,
        request: ClaimPrepareRequest,
        idempotencyKey: String,
        accessToken: String,
    ): ClaimIntentDto =
        post(
            path = "/v1/opportunities/$refreshId/claim",
            accessToken = accessToken,
            idempotencyKey = idempotencyKey,
            requestBody = json.encodeToJsonElement(
                ClaimPrepareRequest.serializer(),
                request,
            ),
            deserializer = ClaimIntentDto.serializer(),
        )

    override suspend fun claimDetail(
        acceptanceId: String,
        accessToken: String,
    ): ClaimStatusDto =
        get(
            path = "/v1/claims/$acceptanceId",
            accessToken = accessToken,
            deserializer = ClaimStatusDto.serializer(),
        )

    override suspend fun observeClaim(
        acceptanceId: String,
        request: ClaimObserveRequest,
        accessToken: String,
    ): ClaimStatusDto =
        post(
            path = "/v1/claims/$acceptanceId/observe",
            accessToken = accessToken,
            requestBody = json.encodeToJsonElement(
                ClaimObserveRequest.serializer(),
                request,
            ),
            deserializer = ClaimStatusDto.serializer(),
        )

    override suspend fun issueEvidenceChallenge(
        acceptanceId: String,
        accessToken: String,
    ): EvidenceChallengeDto =
        post(
            path = "/v1/claims/$acceptanceId/challenge",
            accessToken = accessToken,
            requestBody = kotlinx.serialization.json.buildJsonObject {},
            deserializer = EvidenceChallengeDto.serializer(),
        )

    override suspend fun authorizeEvidenceUpload(
        challengeId: String,
        request: EvidenceUploadAuthorizeRequest,
        accessToken: String,
    ): EvidenceUploadAuthorizationDto =
        post(
            path = "/v1/evidence-challenges/$challengeId/upload",
            accessToken = accessToken,
            requestBody = json.encodeToJsonElement(
                EvidenceUploadAuthorizeRequest.serializer(),
                request,
            ),
            deserializer = EvidenceUploadAuthorizationDto.serializer(),
        )

    override suspend fun commitEvidence(
        evidenceId: String,
        request: EvidenceCommitRequest,
        idempotencyKey: String,
        accessToken: String,
    ): EvidenceCommitDto =
        post(
            path = "/v1/evidence/$evidenceId/commit",
            accessToken = accessToken,
            idempotencyKey = idempotencyKey,
            requestBody = json.encodeToJsonElement(
                EvidenceCommitRequest.serializer(),
                request,
            ),
            deserializer = EvidenceCommitDto.serializer(),
        )

    override suspend fun verifyRefresh(
        refreshId: String,
        accessToken: String,
    ): VerificationResultDto =
        post(
            path = "/v1/refreshes/$refreshId/verify",
            accessToken = accessToken,
            requestBody = kotlinx.serialization.json.buildJsonObject {},
            deserializer = VerificationResultDto.serializer(),
        )

    override suspend fun paymentStatus(
        refreshId: String,
        accessToken: String,
    ): PaymentStatusDto =
        get(
            path = "/v1/refreshes/$refreshId/payment",
            accessToken = accessToken,
            deserializer = PaymentStatusDto.serializer(),
        )

    override suspend fun receipt(
        refreshId: String,
        accessToken: String,
    ): ReceiptDto =
        get(
            path = "/v1/refreshes/$refreshId/receipt",
            accessToken = accessToken,
            deserializer = ReceiptDto.serializer(),
        )

    override suspend fun me(accessToken: String): MeDto =
        get(
            path = "/v1/me",
            accessToken = accessToken,
            deserializer = MeDto.serializer(),
        )


    override suspend fun walletBindingChallenge(
        request: WalletBindingChallengeRequest,
        accessToken: String,
    ): WalletBindingChallengeDto =
        post(
            path = "/v1/wallet-bindings/challenge",
            accessToken = accessToken,
            requestBody = json.encodeToJsonElement(
                WalletBindingChallengeRequest.serializer(),
                request,
            ),
            deserializer = WalletBindingChallengeDto.serializer(),
        )

    override suspend fun verifyWalletBinding(
        request: WalletBindingVerifyRequest,
        accessToken: String,
    ): WalletBindingVerifyDto =
        post(
            path = "/v1/wallet-bindings/verify",
            accessToken = accessToken,
            requestBody = json.encodeToJsonElement(
                WalletBindingVerifyRequest.serializer(),
                request,
            ),
            deserializer = WalletBindingVerifyDto.serializer(),
        )

    override suspend fun createRefresh(
        request: CreateRefreshRequest,
        idempotencyKey: String,
        accessToken: String,
    ): RefreshDto =
        post(
            path = "/v1/refreshes",
            accessToken = accessToken,
            idempotencyKey = idempotencyKey,
            requestBody = json.encodeToJsonElement(
                CreateRefreshRequest.serializer(),
                request,
            ),
            deserializer = RefreshDto.serializer(),
        )

    override suspend fun refreshDetail(
        refreshId: String,
        accessToken: String,
    ): RefreshDto =
        get(
            path = "/v1/refreshes/$refreshId",
            accessToken = accessToken,
            deserializer = RefreshDto.serializer(),
        )

    override suspend fun fundingIntent(
        refreshId: String,
        idempotencyKey: String,
        accessToken: String,
    ): FundingIntentDto =
        post(
            path = "/v1/refreshes/$refreshId/funding-intent",
            accessToken = accessToken,
            idempotencyKey = idempotencyKey,
            requestBody = kotlinx.serialization.json.buildJsonObject {},
            deserializer = FundingIntentDto.serializer(),
        )

    override suspend fun observeFunding(
        refreshId: String,
        request: FundingObserveRequest,
        idempotencyKey: String,
        accessToken: String,
    ): RefreshDto =
        post(
            path = "/v1/refreshes/$refreshId/funding-observe",
            accessToken = accessToken,
            idempotencyKey = idempotencyKey,
            requestBody = json.encodeToJsonElement(
                FundingObserveRequest.serializer(),
                request,
            ),
            deserializer = RefreshDto.serializer(),
        )



    suspend fun experienceGet(path: String, token: String? = null, parameters: Map<String, String> = emptyMap()): kotlinx.serialization.json.JsonObject =
        get(path, token, kotlinx.serialization.json.JsonObject.serializer()) { parameters.forEach { (key, value) -> parameter(key, value) } }

    suspend fun experiencePost(path: String, payload: kotlinx.serialization.json.JsonObject, token: String): kotlinx.serialization.json.JsonObject =
        post(path, token, payload, kotlinx.serialization.json.JsonObject.serializer())

    suspend fun experienceMutate(path: String, payload: kotlinx.serialization.json.JsonObject, token: String,
        method: String, key: String? = null): kotlinx.serialization.json.JsonObject =
        post(path, token, payload, kotlinx.serialization.json.JsonObject.serializer(), key, io.ktor.http.HttpMethod.parse(method))

    private suspend fun <T> post(
        path: String,
        accessToken: String,
        requestBody: kotlinx.serialization.json.JsonElement,
        deserializer: DeserializationStrategy<T>,
        idempotencyKey: String? = null,
        method: io.ktor.http.HttpMethod = io.ktor.http.HttpMethod.Post,
    ): T {
        val baseUrl = config.apiBaseUrl.trim().trimEnd('/')
        if (baseUrl.isEmpty()) {
            throw ApiFailure.Configuration("NOW API base URL is not configured")
        }

        val response = try {
            client.request("$baseUrl$path") {
                this.method = method
                header("x-request-id", UUID.randomUUID().toString())
                bearerAuth(accessToken)
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                idempotencyKey?.let { header("Idempotency-Key", it) }
                setBody(requestBody)
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
            in 500..599 -> ApiFailure.ServerFailure(statusCode, message, code)
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
