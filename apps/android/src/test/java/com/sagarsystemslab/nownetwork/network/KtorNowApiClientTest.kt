package com.sagarsystemslab.nownetwork.network

import com.sagarsystemslab.nownetwork.config.PublicRuntimeConfig
import com.sagarsystemslab.nownetwork.repository.ServerClock
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KtorNowApiClientTest {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    @Test
    fun askAndAvailabilityUseAuthenticatedMutationMethods() = runBlocking {
        val calls = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val api = client(MockEngine { request ->
            calls += request
            respond("""{"request_id":"11111111-1111-4111-8111-111111111111","server_time":"2035-01-01T00:00:00Z","data":{}}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }, ServerClock())
        val body = kotlinx.serialization.json.buildJsonObject {}
        api.experienceMutate("/v1/asks/resolve", body, "test-token", "POST", "same-durable-key")
        api.experienceMutate("/v1/asks/resolve", body, "test-token", "POST", "same-durable-key")
        api.experienceMutate("/v1/me/contributor-presence", body, "test-token", "PUT")
        api.experienceMutate("/v1/me/contributor-presence", body, "test-token", "DELETE")
        assertEquals(listOf(HttpMethod.Post,HttpMethod.Post,HttpMethod.Put,HttpMethod.Delete), calls.map { it.method })
        assertEquals(listOf("same-durable-key","same-durable-key"), calls.take(2).map { it.headers["Idempotency-Key"] })
        assertTrue(calls.all { it.headers[HttpHeaders.Authorization] == "Bearer test-token" })
    }

    @Test
    fun nearbyStateResponseMapsEnvelopeAndRequestContract() = runBlocking {
        val clock = ServerClock()
        val client = client(
            engine = MockEngine { request ->
                assertEquals("/v1/states/nearby", request.url.encodedPath)
                assertEquals("12.5", request.url.parameters["lat"])
                assertEquals("77.25", request.url.parameters["lng"])
                assertEquals("3000", request.url.parameters["radius_m"])
                assertEquals("12", request.url.parameters["limit"])
                assertEquals("cursor-a", request.url.parameters["cursor"])
                assertEquals("parking lot", request.url.parameters["q"])
                assertEquals("stale", request.url.parameters["freshness"])

                val requestId = request.headers["x-request-id"]
                requireNotNull(requestId)
                UUID.fromString(requestId)

                respond(
                    content = """
                        {
                          "request_id":"11111111-1111-4111-8111-111111111111",
                          "server_time":"2035-01-01T00:00:00Z",
                          "data":{
                            "items":[{
                              "state_id":"22222222-2222-4222-8222-222222222222",
                              "title":"Parking Lot B",
                              "question":"How many spaces are available?",
                              "state_type":"NUMERIC",
                              "value":3,
                              "unit_code":"spaces",
                              "freshness_status":"LIVE",
                              "observed_at":"2026-09-26T12:00:00Z",
                              "aging_at":"2026-09-26T12:07:00Z",
                              "fresh_until":"2026-09-26T12:10:00Z",
                              "verification_class":"FAST",
                              "refresh_status":null,
                              "conflict_active":false,
                              "distance_m":84.5,
                              "revision":7
                            }],
                            "next_cursor":"next-a"
                          },
                          "meta":{}
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
            clock = clock,
        )

        val result = client.nearbyStates(
            NearbyStateQuery(
                latitude = 12.5,
                longitude = 77.25,
                radiusMeters = 3_000,
                limit = 12,
                cursor = "cursor-a",
                search = "parking lot",
                freshness = "stale",
            ),
        )

        assertEquals("Parking Lot B", result.items.single().title)
        assertEquals("3", result.items.single().value.toString())
        assertEquals("next-a", result.nextCursor)
        assertNotEquals(0L, clock.offsetMillis())
    }

    @Test
    fun nearbyOpportunityRequestIsAuthenticatedAndDecodesContract() = runBlocking {
        val client = client(
            engine = MockEngine { request ->
                assertEquals("/v1/opportunities/nearby", request.url.encodedPath)
                assertEquals("Bearer token-a", request.headers[HttpHeaders.Authorization])
                assertEquals("12.5", request.url.parameters["lat"])
                assertEquals("77.25", request.url.parameters["lng"])
                assertEquals("payout", request.url.parameters["sort"])
                assertEquals("PARKING", request.url.parameters["category"])

                respond(
                    content = """
                        {
                          "request_id":"11111111-1111-4111-8111-111111111111",
                          "server_time":"2026-09-26T12:00:00Z",
                          "data":{
                            "items":[{
                              "refresh_id":"22222222-2222-4222-8222-222222222222",
                              "state_id":"33333333-3333-4333-8333-333333333333",
                              "state_version":1,
                              "title":"Parking Lot B",
                              "question":"Available spaces",
                              "state_type":"NUMERIC",
                              "unit_code":"spaces",
                              "location":{
                                "location_id":"44444444-4444-4444-8444-444444444444",
                                "name":"Parking Lot B",
                                "location_type":"PARKING",
                                "display_address":"Demo district"
                              },
                              "reward":{
                                "mint":"mint-a",
                                "pool_atomic":"450000",
                                "payout_rule":"EQUAL"
                              },
                              "distance_m":92.0,
                              "expires_at":"2026-09-26T12:10:00Z",
                              "evidence_deadline":"2026-09-26T12:08:00Z",
                              "verification_class":"FAST",
                              "evidence_summary":{
                                "template_key":"parking.photo.v1",
                                "media_required":true,
                                "location_required":true,
                                "required_witnesses":1,
                                "max_witnesses":1
                              },
                              "availability":{
                                "claimable":true,
                                "active_claims":0,
                                "remaining_slots":1
                              },
                              "state_revision":7,
                              "revision":3
                            }],
                            "next_cursor":null
                          },
                          "meta":{}
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )

        val result = client.nearbyOpportunities(
            query = NearbyOpportunityQuery(
                latitude = 12.5,
                longitude = 77.25,
                radiusMeters = 3_000,
                sort = "payout",
                category = "PARKING",
            ),
            accessToken = "token-a",
        )

        assertEquals("Parking Lot B", result.items.single().title)
        assertEquals("450000", result.items.single().reward.poolAtomic)
        assertTrue(result.items.single().availability.claimable)
        val detailJson = json.encodeToString(OpportunityDto.serializer(), result.items.single().copy(distanceM = null))
        assertEquals(null, json.decodeFromString(OpportunityDto.serializer(), detailJson).distanceM)
    }

    @Test
    fun requesterRefreshMutationCarriesAuthAndIdempotency() = runBlocking {
        val client = client(
            engine = MockEngine { request ->
                assertEquals(HttpMethod.Post, request.method)
                assertEquals("/v1/refreshes", request.url.encodedPath)
                assertEquals("Bearer token-a", request.headers[HttpHeaders.Authorization])
                assertEquals("refresh-create:test-1234", request.headers["Idempotency-Key"])
                assertTrue(
                    request.body.contentType
                        ?.toString()
                        ?.startsWith("application/json") == true,
                )

                respond(
                    content = """
                        {
                          "request_id":"11111111-1111-4111-8111-111111111111",
                          "server_time":"2026-09-27T12:00:00Z",
                          "data":{
                            "refresh_id":"22222222-2222-4222-8222-222222222222",
                            "state_id":"33333333-3333-4333-8333-333333333333",
                            "state_version":1,
                            "status":"DRAFT",
                            "verification_class":"FAST",
                            "required_witnesses":1,
                            "max_witnesses":1,
                            "payout_rule":"SINGLE_WINNER_ALL",
                            "proof_policy":{},
                            "proof_policy_digest":"00",
                            "intent_core_hash":"00",
                            "refresh_expires_at":"2035-01-01T00:10:00Z",
                            "evidence_deadline":"2035-01-01T00:08:00Z",
                            "reward_mint":"mint-a",
                            "funding_target_atomic":"450000",
                            "funding_operation_id":null,
                            "chain_total_funded_atomic":"0",
                            "chain_refresh_address":null,
                            "chain_status":null,
                            "chain_observed_at":null,
                            "revision":1,
                            "next_step":"FUNDING_INTENT"
                          },
                          "meta":{}
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.Created,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )

        val result = client.createRefresh(
            request = CreateRefreshRequest(
                stateId = "33333333-3333-4333-8333-333333333333",
                walletBindingId = "44444444-4444-4444-8444-444444444444",
                fundingTargetAtomic = "450000",
            ),
            idempotencyKey = "refresh-create:test-1234",
            accessToken = "token-a",
        )

        assertEquals("DRAFT", result.status)
        assertEquals("450000", result.fundingTargetAtomic)
    }

    @Test
    fun contributorClaimMutationCarriesAuthAndIdempotency() = runBlocking {
        val refreshId = "22222222-2222-4222-8222-222222222222"
        val client = client(
            engine = MockEngine { request ->
                assertEquals(HttpMethod.Post, request.method)
                assertEquals("/v1/opportunities/$refreshId/claim", request.url.encodedPath)
                assertEquals("Bearer token-a", request.headers[HttpHeaders.Authorization])
                assertEquals("claim-prepare:test-1234", request.headers["Idempotency-Key"])
                assertTrue(
                    request.body.contentType
                        ?.toString()
                        ?.startsWith("application/json") == true,
                )

                respond(
                    content = """
                        {
                          "request_id":"11111111-1111-4111-8111-111111111111",
                          "server_time":"2026-09-27T12:00:00Z",
                          "data":{
                            "acceptance_id":"33333333-3333-4333-8333-333333333333",
                            "refresh_id":"$refreshId",
                            "status":"WALLET_PENDING",
                            "claim_slot":null,
                            "claim_duration_seconds":300,
                            "claim_deadline":null,
                            "chain_signature":null,
                            "chain_status":null,
                            "refresh_status":"AVAILABLE",
                            "refresh_expires_at":"2035-01-01T00:10:00Z",
                            "evidence_deadline":"2035-01-01T00:08:00Z",
                            "revision":1,
                            "next_step":"SIGN_OR_OBSERVE_CLAIM",
                            "cluster":"devnet",
                            "program_id":"program-a",
                            "wallet_address":"wallet-a",
                            "reward_mint":"mint-a",
                            "chain_refresh_id_hex":"04",
                            "accounts":{
                              "claimant":"wallet-a",
                              "config":"config-a",
                              "refresh":"refresh-a",
                              "reward_mint":"mint-a",
                              "claimant_reward_token_account":"reward-account-a"
                            },
                            "instruction":{
                              "name":"claim_witness",
                              "refresh_id_hex":"04",
                              "claim_duration_seconds":300
                            }
                          },
                          "meta":{}
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.Created,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )

        val result = client.prepareClaim(
            refreshId = refreshId,
            request = ClaimPrepareRequest(
                walletBindingId = "44444444-4444-4444-8444-444444444444",
            ),
            idempotencyKey = "claim-prepare:test-1234",
            accessToken = "token-a",
        )

        assertEquals("WALLET_PENDING", result.status)
        assertEquals(300L, result.claimDurationSeconds)
        assertEquals("claim_witness", result.instruction.name)
    }

    @Test
    fun evidenceChallengeUsesClaimBoundRoute() = runBlocking {
        val acceptanceId = "33333333-3333-4333-8333-333333333333"
        val client = client(
            engine = MockEngine { request ->
                assertEquals(HttpMethod.Post, request.method)
                assertEquals("/v1/claims/$acceptanceId/challenge", request.url.encodedPath)
                assertEquals("Bearer token-a", request.headers[HttpHeaders.Authorization])

                respond(
                    content = """
                        {
                          "request_id":"11111111-1111-4111-8111-111111111111",
                          "server_time":"2026-09-28T00:00:00Z",
                          "data":{
                            "challenge_id":"44444444-4444-4444-8444-444444444444",
                            "refresh_id":"22222222-2222-4222-8222-222222222222",
                            "acceptance_id":"$acceptanceId",
                            "nonce":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                            "issued_at":"2026-09-28T00:00:00Z",
                            "expires_at":"2026-09-28T00:03:00Z",
                            "policy_version":1,
                            "capture":{
                              "media_required":true,
                              "location_required":true
                            },
                            "claim_status":"CAPTURE_ACTIVE",
                            "claim_revision":3,
                            "refresh_status":"CAPTURE_IN_PROGRESS",
                            "refresh_revision":8
                          },
                          "meta":{}
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.Created,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )

        val result = client.issueEvidenceChallenge(
            acceptanceId = acceptanceId,
            accessToken = "token-a",
        )

        assertEquals(acceptanceId, result.acceptanceId)
        assertTrue(result.capture.locationRequired)
        assertEquals("CAPTURE_ACTIVE", result.claimStatus)
    }

    @Test
    fun evidenceCommitCarriesStableIdempotencyContract() = runBlocking {
        val evidenceId = "55555555-5555-4555-8555-555555555555"
        val client = client(
            engine = MockEngine { request ->
                assertEquals(HttpMethod.Post, request.method)
                assertEquals("/v1/evidence/$evidenceId/commit", request.url.encodedPath)
                assertEquals("Bearer token-a", request.headers[HttpHeaders.Authorization])
                assertEquals("evidence-commit:$evidenceId", request.headers["Idempotency-Key"])

                respond(
                    content = """
                        {
                          "request_id":"11111111-1111-4111-8111-111111111111",
                          "server_time":"2026-09-28T00:00:10Z",
                          "data":{
                            "evidence_id":"$evidenceId",
                            "refresh_id":"22222222-2222-4222-8222-222222222222",
                            "acceptance_id":"33333333-3333-4333-8333-333333333333",
                            "challenge_id":"44444444-4444-4444-8444-444444444444",
                            "evidence_status":"COMMITTED",
                            "claim_status":"EVIDENCE_COMMITTED",
                            "refresh_status":"EVIDENCE_SUBMITTED",
                            "committed_at":"2026-09-28T00:00:10Z",
                            "media":{
                              "object_key":"refreshes/r/evidence/e/original",
                              "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                              "size_bytes":2048,
                              "mime":"image/jpeg"
                            },
                            "replayed":false,
                            "next_step":"VERIFICATION"
                          },
                          "meta":{}
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.Created,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )

        val result = client.commitEvidence(
            evidenceId = evidenceId,
            request = EvidenceCommitRequest(
                nonce = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                mediaSha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                mediaSizeBytes = 2048,
                answerValue = JsonPrimitive(2),
                captureStartedMonotonicMs = 1000,
                captureCompletedMonotonicMs = 1200,
                locationSamples = listOf(
                    EvidenceLocationSampleDto(
                        lat = 12.5,
                        lng = 77.25,
                        accuracyM = 8.0,
                        provider = "fused",
                        mockSignal = false,
                        capturedOffsetMs = 50,
                    ),
                ),
            ),
            idempotencyKey = "evidence-commit:$evidenceId",
            accessToken = "token-a",
        )

        assertEquals("COMMITTED", result.evidenceStatus)
        assertEquals("VERIFICATION", result.nextStep)
        assertEquals(2048L, result.media.sizeBytes)
    }

    @Test
    fun verificationReturnsAuthoritativeProjectedState() = runBlocking {
        val refreshId = "22222222-2222-4222-8222-222222222222"
        val verificationId = "66666666-6666-4666-8666-666666666666"
        val client = client(
            engine = MockEngine { request ->
                assertEquals(HttpMethod.Post, request.method)
                assertEquals("/v1/refreshes/$refreshId/verify", request.url.encodedPath)
                assertEquals("Bearer token-a", request.headers[HttpHeaders.Authorization])

                respond(
                    content = """
                        {
                          "request_id":"11111111-1111-4111-8111-111111111111",
                          "server_time":"2026-09-28T09:00:00Z",
                          "data":{
                            "verification_result_id":"$verificationId",
                            "refresh_id":"$refreshId",
                            "result":"VERIFIED",
                            "status":"VERIFIED",
                            "reason_codes":[],
                            "evidence_ids":[
                              "55555555-5555-4555-8555-555555555555"
                            ],
                            "final_answer":2,
                            "evidence_set_revision":1,
                            "policy_version":1,
                            "canonical_digest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                            "completed_at":"2026-09-28T09:00:00Z",
                            "refresh_status":"VERIFIED",
                            "refresh_revision":10,
                            "replayed":false,
                            "next_step":"PROJECTION",
                            "state_projection":{
                              "state_id":"33333333-3333-4333-8333-333333333333",
                              "refresh_id":"$refreshId",
                              "verification_result_id":"$verificationId",
                              "state_revision":8,
                              "history_id":"77777777-7777-4777-8777-777777777777",
                              "observed_at":"2026-09-28T09:00:00Z",
                              "aging_at":"2026-09-28T09:07:00Z",
                              "fresh_until":"2026-09-28T09:10:00Z",
                              "current_value":{
                                "kind":"numeric",
                                "scaled_value":"2",
                                "scale":0,
                                "unit":"spaces"
                              },
                              "projected":true,
                              "replayed":false,
                              "superseded":false,
                              "freshness":"LIVE"
                            }
                          },
                          "meta":{}
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.Created,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )

        val result = client.verifyRefresh(
            refreshId = refreshId,
            accessToken = "token-a",
        )

        assertEquals("VERIFIED", result.result)
        assertEquals("LIVE", result.stateProjection?.freshness)
        assertEquals(8L, result.stateProjection?.stateRevision)
        assertEquals(1, result.evidenceIds.size)
    }

    @Test
    fun paymentStatusUsesAuthorizedReadRoute() = runBlocking {
        val refreshId = "81000000-0000-4000-8000-000000000001"
        val client = client(
            engine = MockEngine { request ->
                assertEquals(HttpMethod.Get, request.method)
                assertEquals("/v1/refreshes/$refreshId/payment", request.url.encodedPath)
                assertEquals("Bearer token-a", request.headers[HttpHeaders.Authorization])

                respond(
                    content = """
                        {
                          "request_id":"11111111-1111-4111-8111-111111111111",
                          "server_time":"2026-09-29T10:00:00Z",
                          "data":{
                            "refresh_id":"$refreshId",
                            "verification_result_id":"82000000-0000-4000-8000-000000000001",
                            "settlement_id":"83000000-0000-4000-8000-000000000001",
                            "settlement_status":"VERIFYING",
                            "payment_status":"VERIFYING",
                            "chain_signature":"signature-a",
                            "chain_commitment":null,
                            "confirmed_at":null,
                            "finalized_at":null,
                            "updated_at":"2026-09-29T10:00:00Z"
                          },
                          "meta":{}
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )

        val result = client.paymentStatus(
            refreshId = refreshId,
            accessToken = "token-a",
        )

        assertEquals("VERIFYING", result.paymentStatus)
        assertEquals("VERIFYING", result.settlementStatus)
        assertEquals("signature-a", result.chainSignature)
    }

    @Test
    fun receiptUsesAuthorizedReadRouteAndDecodesFinalProof() = runBlocking {
        val refreshId = "91000000-0000-4000-8000-000000000001"
        val client = client(
            engine = MockEngine { request ->
                assertEquals(HttpMethod.Get, request.method)
                assertEquals("/v1/refreshes/$refreshId/receipt", request.url.encodedPath)
                assertEquals("Bearer token-a", request.headers[HttpHeaders.Authorization])

                respond(
                    content = """
                        {
                          "request_id":"11111111-1111-4111-8111-111111111111",
                          "server_time":"2026-09-29T11:00:00Z",
                          "data":{
                            "receipt_id":"95000000-0000-4000-8000-000000000001",
                            "refresh_id":"$refreshId",
                            "state_id":"92000000-0000-4000-8000-000000000001",
                            "verification_result_id":"93000000-0000-4000-8000-000000000001",
                            "settlement_id":"94000000-0000-4000-8000-000000000001",
                            "status":"FINAL",
                            "final_value":{"kind":"numeric","value":12},
                            "observed_at":"2026-09-29T10:58:00Z",
                            "verification_class":"FAST",
                            "reward_amount_atomic":"500000",
                            "reward_mint":"So11111111111111111111111111111111111111112",
                            "verification_digest":"abababababababababababababababababababababababababababababababab",
                            "settlement_operation_hash":"cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd",
                            "receipt_digest":"efefefefefefefefefefefefefefefefefefefefefefefefefefefefefefefef",
                            "settlement_signature":"signature-final",
                            "chain_commitment":"finalized",
                            "finalized_at":"2026-09-29T11:00:00Z",
                            "revision":1
                          },
                          "meta":{}
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )

        val result = client.receipt(
            refreshId = refreshId,
            accessToken = "token-a",
        )

        assertEquals("FINAL", result.status)
        assertEquals("finalized", result.chainCommitment)
        assertEquals("500000", result.rewardAmountAtomic)
        assertEquals(1L, result.revision)
    }

    @Test
    fun unauthorizedResponseMapsToAuthExpired() = runBlocking {
        val client = client(
            engine = MockEngine {
                respond(
                    content = """
                        {
                          "request_id":"11111111-1111-4111-8111-111111111111",
                          "server_time":"2026-09-26T12:00:00Z",
                          "error":{
                            "code":"AUTH_REQUIRED",
                            "message":"Authentication required.",
                            "safe_to_retry":false,
                            "retry_after_ms":null
                          }
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.Unauthorized,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )

        val error = runCatching { client.me("expired-token") }.exceptionOrNull()

        assertTrue(error is ApiFailure.AuthExpired)
    }

    @Test
    fun safeGetRetriesOneTransientServerFailure() = runBlocking {
        var calls = 0
        val client = client(
            engine = MockEngine {
                calls += 1
                if (calls == 1) {
                    respond(
                        content = """
                            {
                              "request_id":"11111111-1111-4111-8111-111111111111",
                              "server_time":"2026-09-26T12:00:00Z",
                              "error":{
                                "code":"TEMPORARY",
                                "message":"Try again.",
                                "safe_to_retry":true,
                                "retry_after_ms":null
                              }
                            }
                        """.trimIndent(),
                        status = HttpStatusCode.ServiceUnavailable,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                } else {
                    respond(
                        content = """
                            {
                              "request_id":"11111111-1111-4111-8111-111111111111",
                              "server_time":"2026-09-26T12:00:00Z",
                              "data":{
                                "actor_id":"33333333-3333-4333-8333-333333333333",
                                "status":"ACTIVE",
                                "wallet_bindings":[]
                              },
                              "meta":{}
                            }
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            },
        )

        val result = client.me("valid-token")

        assertEquals("ACTIVE", result.status)
        assertEquals(2, calls)
    }

    @Test
    fun malformedEnvelopeFailsAsProtocolError() = runBlocking {
        val client = client(
            engine = MockEngine {
                respond(
                    content = """{"unexpected":true}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )

        val error = runCatching {
            client.stateDetail("22222222-2222-4222-8222-222222222222")
        }.exceptionOrNull()

        assertTrue(error is ApiFailure.ProtocolError)
    }

    private fun client(
        engine: MockEngine,
        clock: ServerClock = ServerClock(),
    ): KtorNowApiClient =
        KtorNowApiClient(
            client = HttpClient(engine) {
                expectSuccess = false
                install(ContentNegotiation) {
                    json(json)
                }
            },
            json = json,
            config = PublicRuntimeConfig(
                apiBaseUrl = "https://now.example",
                supabaseUrl = "",
                supabasePublishableKey = "",
                solanaCluster = "devnet",
            ),
            serverClock = clock,
        )
}
