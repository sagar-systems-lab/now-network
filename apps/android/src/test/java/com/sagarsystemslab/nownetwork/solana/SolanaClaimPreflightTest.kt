package com.sagarsystemslab.nownetwork.solana

import com.sagarsystemslab.nownetwork.config.SolanaRuntimeConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SolanaClaimPreflightTest {
    private val transaction = ByteArray(65).also { it[0] = 1 } + byteArrayOf(1, 2, 3)

    @Test
    fun emptyWalletIsBlockedBeforeSimulationWithFeeAndRentAmount() = runBlocking {
        val fixture = fixture(0, false)
        try {
            val error = runCatching { fixture.api.preflightClaim("wallet", "reward", transaction) }.exceptionOrNull()
            assertTrue(error is SolanaRpcFailure.Rejected)
            assertTrue(error?.message.orEmpty().contains("0.00204428 SOL"))
            assertTrue(error?.message.orEmpty().contains("devnet"))
            assertTrue("simulateTransaction" !in fixture.calls)
        } finally { fixture.client.close() }
    }

    @Test
    fun fundedWalletCanSimulateAndExistingRewardAccountNeedsNoNewRent() = runBlocking {
        for ((balance, exists) in listOf(2_044_280L to false, 5_000L to true)) {
            val fixture = fixture(balance, exists)
            try {
                fixture.api.preflightClaim("wallet", "reward", transaction)
                assertEquals(exists, "getMinimumBalanceForRentExemption" !in fixture.calls)
                assertTrue("simulateTransaction" in fixture.calls)
            } finally { fixture.client.close() }
        }
    }

    @Test
    fun expiredOnChainClaimIsRejectedBeforeWalletHandoff() = runBlocking {
        val fixture = fixture(10_000_000L, true, true)
        try {
            val error = runCatching { fixture.api.preflightClaim("wallet", "reward", transaction) }.exceptionOrNull()
            assertTrue(error is SolanaRpcFailure.Rejected)
            assertTrue(error?.message.orEmpty().contains("claim window has ended"))
        } finally { fixture.client.close() }
    }

    private fun fixture(balance: Long, rewardExists: Boolean, expired: Boolean = false): Fixture {
        val calls = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            val method = body.getValue("method").jsonPrimitive.content
            calls += method
            val result = when (method) {
                "getBalance" -> "{\"value\":$balance}"
                "getAccountInfo" -> "{\"value\":${if (rewardExists) "{}" else "null"}}"
                "getMinimumBalanceForRentExemption" -> "2039280"
                "getFeeForMessage" -> { assertTrue(body.getValue("params").toString().contains("AQID")); "{\"value\":5000}" }
                "simulateTransaction" -> if (expired)
                    "{\"value\":{\"err\":{\"InstructionError\":[1,{\"Custom\":6000}]},\"logs\":[\"ClaimDeadlineAfterRefreshExpiry\"]}}"
                    else "{\"value\":{\"err\":null,\"logs\":[]}}"
                else -> throw AssertionError("Unexpected RPC method $method")
            }
            respond("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":$result}", HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(Json) } }
        val config = SolanaRuntimeConfig("devnet", "https://example.test", "program", "https://example.test", "icon.png")
        return Fixture(KtorSolanaRpcClient(client, Json, config), client, calls)
    }

    private data class Fixture(val api: KtorSolanaRpcClient, val client: HttpClient, val calls: MutableList<String>)
}
