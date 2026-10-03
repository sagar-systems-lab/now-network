package com.sagarsystemslab.nownetwork.experience

import android.content.Context
import android.os.Build
import android.util.Base64
import com.sagarsystemslab.nownetwork.BuildConfig
import com.sagarsystemslab.nownetwork.auth.AuthGateway
import com.sagarsystemslab.nownetwork.config.RewardDisplayConfig
import com.sagarsystemslab.nownetwork.config.SolanaRuntimeConfig
import com.sagarsystemslab.nownetwork.data.local.ActiveOperationDao
import com.sagarsystemslab.nownetwork.network.*
import com.sagarsystemslab.nownetwork.repository.SessionRepository
import com.sagarsystemslab.nownetwork.wallet.*
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import java.math.BigDecimal
import java.math.BigInteger
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

fun JsonObject.text(key: String, fallback: String = ""): String = (get(key) as? JsonPrimitive)?.contentOrNull ?: fallback
fun JsonObject.number(key: String): Int = (get(key) as? JsonPrimitive)?.intOrNull ?: 0
fun JsonObject.flag(key: String): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull ?: false
fun JsonObject.objectAt(key: String): JsonObject = get(key) as? JsonObject ?: JsonObject(emptyMap())
fun JsonObject.rows(key: String = "items"): List<JsonObject> = (get(key) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

@Singleton
class ExperienceRepository @Inject constructor(
    private val api: KtorNowApiClient,
    private val auth: AuthGateway,
    private val sessions: SessionRepository,
    private val wallets: WalletRequestCoordinator,
    private val config: SolanaRuntimeConfig,
    private val rewards: RewardDisplayConfig,
    private val http: HttpClient,
    private val json: Json,
    private val operations: ActiveOperationDao,
    private val evidence: com.sagarsystemslab.nownetwork.data.local.PendingEvidenceDao,
    private val outbox: com.sagarsystemslab.nownetwork.data.local.OutboxDao,
    @ApplicationContext private val context: Context,
) {
    private val walletMutex = Mutex()
    suspend fun get(path: String, params: Map<String, String> = emptyMap(), public: Boolean = false): JsonObject =
        if (public) api.experienceGet(path, parameters = params) else authenticated { api.experienceGet(path, it, params) }

    suspend fun post(path: String, payload: JsonObject): JsonObject = authenticated { api.experiencePost(path, payload, it) }
    suspend fun me(): MeDto = authenticated(api::me)

    private suspend fun <T> authenticated(block: suspend (String) -> T): T {
        val session = auth.currentSession() ?: auth.ensureAnonymousSession()
        return try { block(session.accessToken) } catch (expired: ApiFailure.AuthExpired) {
            block(auth.refreshIfNeeded().accessToken)
        }
    }

    suspend fun registerInstallation(pushPermission: Boolean = NowPush.configured && androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled(), pushToken: String? = com.sagarsystemslab.nownetwork.security.AndroidKeystoreSecretStore(context).read("push_token")) {
        val session = auth.currentSession() ?: return
        val claim = runCatching {
            json.parseToJsonElement(String(Base64.decode(session.accessToken.split('.')[1], Base64.URL_SAFE or Base64.NO_WRAP))).jsonObject.text("session_id")
        }.getOrDefault(session.authSubjectId)
        val prefs = context.getSharedPreferences("now_installation", Context.MODE_PRIVATE)
        val key = "installation_$claim"
        val id = prefs.getString(key, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(key, it).apply() }
        prefs.edit().putString("current_installation", id).apply()
        post("/v1/me/installations", buildJsonObject {
            put("installation_id", id); put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}".take(100))
            put("app_version", BuildConfig.VERSION_NAME); put("push_permission", pushPermission)
            pushToken?.let { put("push_token", it) }
        })
    }

    suspend fun connectWallet(host: WalletInteractionHost): MeDto = walletMutex.withLock {
        val account = wallets.connect(host).required()
        val me = me()
        val bound = me.walletBindings.any { it.status == "ACTIVE" && it.cluster == config.cluster && it.walletAddress == account.address }
        if (!bound) {
            val challenge = authenticated { api.walletBindingChallenge(WalletBindingChallengeRequest(account.address, config.cluster), it) }
            val proof = wallets.signWalletProof(host, challenge.message).required()
            check(proof.account.address == account.address) { "The wallet changed. Reconnect and try again." }
            val verified = authenticated { api.verifyWalletBinding(WalletBindingVerifyRequest(challenge.challengeId, proof.signatureBase64), it) }
            check(verified.walletBinding.walletAddress == account.address && verified.walletBinding.cluster == config.cluster && verified.walletBinding.status == "ACTIVE") { "The wallet response could not be verified." }
        }
        sessions.reloadIdentity()
        me()
    }

    suspend fun disconnectWallet(host: WalletInteractionHost) = walletMutex.withLock {
        wallets.disconnect(host).required()
    }

    suspend fun safeSignOut() {
        val protected = operations.listAll().any { it.localState.uppercase() !in setOf("ACKNOWLEDGED","COMPLETED","PAID","REFUNDED","REJECTED","EXPIRED","CANCELLED") }
        check(!protected && evidence.observePending().first().isEmpty() && outbox.observeOutstanding().first().isEmpty()) { "Finish or reconcile your pending work before signing out. Your proof and payments are preserved." }
        check(me().walletBindings.any { it.status == "ACTIVE" }) { "Link a recovery wallet before signing out of this anonymous account." }
        val current = context.getSharedPreferences("now_installation", Context.MODE_PRIVATE).getString("current_installation", null)
        if (current != null) post("/v1/me/installations/revoke", buildJsonObject { put("installation_id", current) })
        sessions.signOutLocal()
        // Server receipts remain durable; actorless local recovery rows cannot cross account boundaries.
        operations.listAll().forEach { operations.delete(it.operationId) }
    }

    fun amount(atomic: String, mint: String): String {
        val integer = atomic.toBigIntegerOrNull() ?: return "Unavailable"
        if (!rewards.matches(mint)) return "$integer atomic · ${mint.take(6)}…"
        return BigDecimal(integer, rewards.decimals).stripTrailingZeros().toPlainString() + " " + rewards.symbol
    }

    suspend fun balances(wallet: WalletBindingDto): Pair<String, String> {
        check(wallet.cluster == config.cluster) { "Select the configured network to view balances." }
        suspend fun rpc(method: String, params: JsonArray): JsonElement {
            val response = http.post(config.rpcUrl) {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("jsonrpc", "2.0"); put("id", 1); put("method", method); put("params", params) })
            }
            check(response.status.isSuccess()) { "Balance service is unavailable." }
            val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
            check(payload["error"] == null) { "Balance service rejected this request." }
            return requireNotNull(payload["result"])
        }
        val sol = rpc("getBalance", buildJsonArray { add(wallet.walletAddress); add(buildJsonObject { put("commitment", "confirmed") }) }).jsonObject.text("value")
        val solText = sol.toBigIntegerOrNull()?.let { BigDecimal(it, 9).stripTrailingZeros().toPlainString() + " SOL" } ?: "Unavailable"
        if (rewards.mint.isBlank()) return solText to "Reward mint not configured"
        val tokens = rpc("getTokenAccountsByOwner", buildJsonArray {
            add(wallet.walletAddress); add(buildJsonObject { put("mint", rewards.mint) }); add(buildJsonObject { put("encoding", "jsonParsed"); put("commitment", "confirmed") })
        }).jsonObject.rows("value")
        val total = tokens.fold(BigInteger.ZERO) { sum, item ->
            val token = item.objectAt("account").objectAt("data").objectAt("parsed").objectAt("info").objectAt("tokenAmount")
            check(token.number("decimals") == rewards.decimals) { "Token decimals do not match the configured mint." }
            sum + (token.text("amount").toBigIntegerOrNull() ?: error("Invalid token balance"))
        }
        return solText to amount(total.toString(), rewards.mint)
    }

    private fun <T> WalletResult<T>.required(): T = when (this) {
        is WalletResult.Success -> value
        WalletResult.UserRejected -> error("Wallet request cancelled. Nothing was submitted.")
        WalletResult.NoWalletFound -> error("Install a compatible Solana wallet to continue.")
        WalletResult.Busy -> error("Another wallet request is already open.")
        else -> error("Wallet connection failed. Please try again.")
    }
}
