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
import java.math.BigDecimal
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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
    suspend fun preflightClaim(walletAddress: String, rewardAccount: String, transaction: ByteArray)

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

    override suspend fun preflightClaim(walletAddress: String, rewardAccount: String, transaction: ByteArray) {
        val balance = request("getBalance", buildJsonArray {
            add(kotlinx.serialization.json.JsonPrimitive(walletAddress))
            add(buildJsonObject { put("commitment", "confirmed") })
        }).jsonObject["value"]?.jsonPrimitive?.long
            ?: throw SolanaRpcFailure.Protocol("Claim balance response is missing value")
        val account = request("getAccountInfo", buildJsonArray {
            add(kotlinx.serialization.json.JsonPrimitive(rewardAccount))
            add(buildJsonObject { put("encoding", "base64"); put("commitment", "confirmed") })
        }).jsonObject["value"]
            ?: throw SolanaRpcFailure.Protocol("Reward account response is missing value")
        val rent = if (account == JsonNull) request("getMinimumBalanceForRentExemption", buildJsonArray {
            add(kotlinx.serialization.json.JsonPrimitive(165))
            add(buildJsonObject { put("commitment", "confirmed") })
        }).jsonPrimitive.long else 0L
        val encodedMessage = Base64.getEncoder().encodeToString(unsignedMessage(transaction))
        val feeValue = request("getFeeForMessage", buildJsonArray {
            add(kotlinx.serialization.json.JsonPrimitive(encodedMessage))
            add(buildJsonObject { put("commitment", "confirmed") })
        }).jsonObject["value"]
        if (feeValue == null || feeValue == JsonNull) {
            throw SolanaRpcFailure.Rejected("Claim transaction expired before signing. Refresh the task and retry.")
        }
        val required = Math.addExact(rent, feeValue.jsonPrimitive.long)
        if (balance < required) {
            val have = BigDecimal.valueOf(balance, 9).stripTrailingZeros().toPlainString()
            val need = BigDecimal.valueOf(required, 9).stripTrailingZeros().toPlainString()
            val tokenSetup = if (rent > 0) " and one-time reward account rent" else ""
            throw SolanaRpcFailure.Rejected("Your ${config.cluster} wallet has $have SOL; this claim needs at least $need SOL for the network fee$tokenSetup. Add SOL on ${config.cluster} to this payout wallet, then check the claim and retry. Nothing was submitted.")
        }
        val simulation = request("simulateTransaction", buildJsonArray {
            add(kotlinx.serialization.json.JsonPrimitive(Base64.getEncoder().encodeToString(transaction)))
            add(buildJsonObject { put("encoding", "base64"); put("commitment", "confirmed"); put("sigVerify", false) })
        }).jsonObject["value"]?.jsonObject
            ?: throw SolanaRpcFailure.Protocol("Claim simulation response is missing value")
        val failure = simulation["err"]
            ?: throw SolanaRpcFailure.Protocol("Claim simulation response is missing error field")
        if (failure != JsonNull) {
            val logs = simulation["logs"]?.toString().orEmpty()
            val reason = when {
                "RefreshExpired" in logs || "ClaimDeadlineAfterRefreshExpiry" in logs -> "This task's claim window has ended. Return to EARN for a current task."
                "SelfClaimProhibited" in logs -> "The requester wallet cannot claim its own task. Use a separate contributor wallet."
                "AlreadyClaimed" in logs -> "This wallet already claimed the task. Check the saved claim before retrying."
                "insufficient lamports" in logs -> "Not enough ${config.cluster} SOL for this claim. Add SOL to the payout wallet and retry."
                else -> "Claim simulation failed ($failure). Refresh the task before retrying."
            }
            throw SolanaRpcFailure.Rejected("$reason Nothing was submitted.")
        }
    }

    private fun unsignedMessage(transaction: ByteArray): ByteArray {
        var count = 0
        var offset = 0
        var shift = 0
        while (true) {
            if (offset >= transaction.size || shift > 14) throw SolanaRpcFailure.Protocol("Invalid transaction signature header")
            val byte = transaction[offset++].toInt() and 255
            count = count or ((byte and 127) shl shift)
            if ((byte and 128) == 0) break
            shift += 7
        }
        val messageStart = offset + count * 64
        if (count !in 1..255 || messageStart >= transaction.size) throw SolanaRpcFailure.Protocol("Invalid transaction message")
        return transaction.copyOfRange(messageStart, transaction.size)
    }

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
