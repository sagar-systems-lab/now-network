package com.sagarsystemslab.nownetwork.solana

import com.sagarsystemslab.nownetwork.config.SolanaRuntimeConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

data class LatestBlockhash(
    val blockhash: String,
    val lastValidBlockHeight: Long,
)

sealed class SolanaRpcFailure(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class Configuration(message: String) : SolanaRpcFailure(message)
    class Network(cause: Throwable) : SolanaRpcFailure("Solana RPC is unreachable", cause)
    class Protocol(message: String, cause: Throwable? = null) : SolanaRpcFailure(message, cause)
    class Rejected(message: String) : SolanaRpcFailure(message)
}

interface SolanaRpcClient {
    suspend fun latestBlockhash(): LatestBlockhash

    suspend fun recentSignatures(
        address: String,
        limit: Int = 12,
    ): List<String>
}

@Singleton
class KtorSolanaRpcClient @Inject constructor(
    private val client: HttpClient,
    private val json: Json,
    private val config: SolanaRuntimeConfig,
) : SolanaRpcClient {
    private val requestIds = AtomicLong(1L)

    override suspend fun latestBlockhash(): LatestBlockhash {
        val result = request(
            method = "getLatestBlockhash",
            params = buildJsonArray {
                add(
                    buildJsonObject {
                        put("commitment", "confirmed")
                    },
                )
            },
        ).jsonObject

        val value = result["value"]?.jsonObject
            ?: throw SolanaRpcFailure.Protocol("getLatestBlockhash missing value")

        return LatestBlockhash(
            blockhash = value["blockhash"]?.jsonPrimitive?.content
                ?: throw SolanaRpcFailure.Protocol("getLatestBlockhash missing blockhash"),
            lastValidBlockHeight = value["lastValidBlockHeight"]?.jsonPrimitive?.long
                ?: throw SolanaRpcFailure.Protocol(
                    "getLatestBlockhash missing lastValidBlockHeight",
                ),
        )
    }

    override suspend fun recentSignatures(
        address: String,
        limit: Int,
    ): List<String> {
        require(limit in 1..100)

        val result = request(
            method = "getSignaturesForAddress",
            params = buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive(address))
                add(
                    buildJsonObject {
                        put("limit", limit)
                        put("commitment", "confirmed")
                    },
                )
            },
        ).jsonArray

        return result.mapNotNull { item ->
            val row = item.jsonObject
            val error = row["err"]
            if (error != null && error.toString() != "null") {
                null
            } else {
                row["signature"]?.jsonPrimitive?.content
            }
        }
    }

    private suspend fun request(
        method: String,
        params: JsonArray,
    ): kotlinx.serialization.json.JsonElement {
        if (!config.configured) {
            throw SolanaRpcFailure.Configuration("Solana runtime is not configured")
        }

        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", requestIds.getAndIncrement())
            put("method", method)
            put("params", params)
        }

        val response = try {
            client.post(config.rpcUrl) {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(payload)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: HttpRequestTimeoutException) {
            throw SolanaRpcFailure.Network(error)
        } catch (error: SocketTimeoutException) {
            throw SolanaRpcFailure.Network(error)
        } catch (error: IOException) {
            throw SolanaRpcFailure.Network(error)
        }

        val root = try {
            json.parseToJsonElement(response.bodyAsText()).jsonObject
        } catch (error: Exception) {
            throw SolanaRpcFailure.Protocol("Solana RPC returned malformed JSON", error)
        }

        root["error"]?.jsonObject?.let { error ->
            val code = error["code"]?.jsonPrimitive?.int ?: 0
            val message = error["message"]?.jsonPrimitive?.content ?: "RPC request rejected"
            throw SolanaRpcFailure.Rejected("$code: $message")
        }

        return root["result"]
            ?: throw SolanaRpcFailure.Protocol("Solana RPC response is missing result")
    }
}
