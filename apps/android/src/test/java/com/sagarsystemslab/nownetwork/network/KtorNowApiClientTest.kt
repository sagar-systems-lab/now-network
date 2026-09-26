package com.sagarsystemslab.nownetwork.network

import com.sagarsystemslab.nownetwork.config.PublicRuntimeConfig
import com.sagarsystemslab.nownetwork.repository.ServerClock
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KtorNowApiClientTest {
    private val json = Json {
        ignoreUnknownKeys = true
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
            ),
            accessToken = "token-a",
        )

        assertEquals("Parking Lot B", result.items.single().title)
        assertEquals("450000", result.items.single().reward.poolAtomic)
        assertTrue(result.items.single().availability.claimable)
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
            client = HttpClient(engine) { expectSuccess = false },
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
