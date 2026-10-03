package com.sagarsystemslab.nownetwork.experience

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sagarsystemslab.nownetwork.network.MeDto
import com.sagarsystemslab.nownetwork.network.WalletBindingDto
import com.sagarsystemslab.nownetwork.wallet.WalletInteractionHost
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

data class ExperienceUiState(
    val history: Map<String, JsonObject> = emptyMap(),
    val historyErrors: Map<String, String> = emptyMap(),
    val refreshDetails: Map<String, JsonObject> = emptyMap(),
    val privateProof: JsonObject? = null,
    val me: MeDto? = null,
    val profile: JsonObject = JsonObject(emptyMap()),
    val preferences: JsonObject = JsonObject(emptyMap()),
    val inbox: JsonObject = JsonObject(emptyMap()),
    val activity: JsonObject = JsonObject(emptyMap()),
    val installations: List<JsonObject> = emptyList(),
    val areas: List<JsonObject> = emptyList(),
    val nextAreaOffset: Int? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val areasLoading: Boolean = false,
    val error: String? = null,
    val areaError: String? = null,
    val notice: String? = null,
    val balances: Map<String, Pair<String, String>> = emptyMap(),
)

@HiltViewModel
class ExperienceViewModel @Inject constructor(
    private val repository: ExperienceRepository,
    private val sessions: com.sagarsystemslab.nownetwork.repository.SessionRepository,
    val browseContext: BrowseContextStore,
) : ViewModel() {
    private val mutable = MutableStateFlow(ExperienceUiState())
    val state = mutable.asStateFlow()
    private var areaJob: Job? = null
    private var refreshJob: Job? = null
    private var mutationJob: Job? = null
    private var identityMutation = false
    private var lastAreaQuery = ""
    private var lastAreaFilter = "all"
    private val accountMutex = kotlinx.coroutines.sync.Mutex()
    private var lastInboxFilter = "all"

    init {
        viewModelScope.launch {
            sessions.state.collectLatest { session ->
                if (!identityMutation && session is com.sagarsystemslab.nownetwork.repository.SessionBootstrapState.Ready && mutable.value.me != null && mutable.value.me?.actorId != session.actorId) {
                    refreshJob?.cancel()
                    mutationJob?.cancel()
                    mutable.value = ExperienceUiState()
                    refresh()
                } else if (!identityMutation && session is com.sagarsystemslab.nownetwork.repository.SessionBootstrapState.Idle) {
                    refreshJob?.cancel()
                    mutationJob?.cancel()
                    mutable.value = ExperienceUiState()
                }
            }
        }
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            mutable.update { it.copy(loading = true, error = null) }
            try {
                withContext(Dispatchers.IO) { accountMutex.withLock {
                    val me = repository.me()
                    if (mutable.value.me?.actorId != me.actorId) { mutable.value = ExperienceUiState(me = me, loading = true) }
                    val profile = repository.get("/v1/me/profile")
                    mutable.update { it.copy(me = me, profile = profile, preferences = profile.objectAt("preferences")) }
                    val inbox = repository.get("/v1/me/notifications", mapOf("filter" to lastInboxFilter))
                    val activity = repository.get("/v1/me/activity")
                    mutable.update { it.copy(inbox = inbox, activity = activity) }
                    try { repository.registerInstallation() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { /* Device settings expose registration errors on retry. */ }
                } }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { mutable.update { if (error is com.sagarsystemslab.nownetwork.network.ApiFailure.AuthExpired) ExperienceUiState(error = safeMessage(error)) else it.copy(error = safeMessage(error)) } }
            finally { mutable.update { it.copy(loading = false) } }
        }
    }

    fun areas(query: String = lastAreaQuery, more: Boolean = false, filter: String = lastAreaFilter) {
        areaJob?.cancel(); lastAreaQuery = query; lastAreaFilter = filter
        val offset = if (more) mutable.value.nextAreaOffset ?: return else 0
        areaJob = viewModelScope.launch {
            mutable.update { it.copy(areasLoading = true, areaError = null, areas = if (more) it.areas else emptyList()) }
            try {
                val response = withContext(Dispatchers.IO) { repository.get("/v1/areas", mapOf("q" to query, "offset" to offset.toString(), "filter" to filter), public = true) }
                mutable.update { it.copy(areas = if (more) (it.areas + response.rows()).distinctBy { row -> row.text("area_id") } else response.rows(), nextAreaOffset = response["next_offset"]?.jsonPrimitive?.intOrNull) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { mutable.update { it.copy(areaError = safeMessage(error)) } }
            finally { mutable.update { it.copy(areasLoading = false) } }
        }
    }
    fun selectArea(area: JsonObject) {
        val center = area.objectAt("center")
        browseContext.select(area.text("name"), center.text("latitude").toDouble(), center.text("longitude").toDouble(), area.number("radius_m"))
    }
    fun saveName(name: String) = mutation("Profile saved") {
        val profile = repository.post("/v1/me/profile", buildJsonObject { put("display_name", name) })
        mutable.update { it.copy(profile = profile) }
    }
    fun avatar(base64: String?) = mutation("Profile photo updated") {
        val response = repository.post("/v1/me/profile/avatar", buildJsonObject { if (base64 == null) put("remove", true) else put("png_base64", base64) })
        mutable.update { it.copy(profile = response) }
    }
    fun savePreferences(preferences: JsonObject) = mutation("Preferences saved") {
        val response = repository.post("/v1/me/preferences", preferences)
        mutable.update { it.copy(preferences = response) }
    }
    fun inbox(filter: String = lastInboxFilter, more: Boolean = false) = mutation(null) {
        val previous = mutable.value.inbox
        val params = mutableMapOf("filter" to filter)
        if (more && filter == lastInboxFilter) {
            params["offset"] = previous.text("next_offset").takeIf { it.isNotBlank() } ?: return@mutation
            params["snapshot_at"] = previous.text("snapshot_at")
        }
        lastInboxFilter = filter
        val response = repository.get("/v1/me/notifications", params)
        mutable.update { it.copy(inbox = if (more) JsonObject(response + ("items" to JsonArray((previous.rows() + response.rows()).distinctBy { row -> row.text("notification_id") }))) else response) }
    }
    fun activity(more: Boolean = false) = mutation(null) {
        val previous = mutable.value.activity
        val params = if (more) mapOf("offset" to (previous.text("next_offset").takeIf { it.isNotBlank() } ?: return@mutation)) else emptyMap()
        val response = repository.get("/v1/me/activity", params)
        mutable.update { it.copy(activity = if (more) JsonObject(response + ("items" to JsonArray((previous.rows() + response.rows()).distinctBy { row -> row.text("refresh_id") }))) else response) }
    }

    fun markRead(id: String? = null) = mutation(null) {
        val snapshot = mutable.value.inbox.text("snapshot_at")
        repository.post("/v1/me/notifications/read", buildJsonObject { if (id != null) put("notification_id", id) else put("through", snapshot) })
        val response = repository.get("/v1/me/notifications", mapOf("filter" to lastInboxFilter))
        mutable.update { it.copy(inbox = response) }
    }
    fun devices() = mutation(null) {
        repository.registerInstallation()
        val response = repository.get("/v1/me/installations")
        mutable.update { it.copy(installations = response.rows()) }
    }
    fun revokeDevice(id: String) = mutation("Device access revoked", changesIdentity = true) {
        if (mutable.value.installations.any { it.text("installation_id") == id && it.flag("current") }) {
            repository.safeSignOut()
            mutable.value = ExperienceUiState(notice = "This device is signed out. Recover your account with a linked wallet.")
        } else {
            repository.post("/v1/me/installations/revoke", buildJsonObject { put("installation_id", id) })
            val response = repository.get("/v1/me/installations")
            mutable.update { it.copy(installations = response.rows()) }
        }
    }
    fun connectWallet(host: WalletInteractionHost) = mutation("Wallet ownership verified", changesIdentity = true) {
        val me = repository.connectWallet(host)
        if (mutable.value.me?.actorId != me.actorId) { mutable.value = ExperienceUiState(me = me, saving = true) }
        else mutable.update { it.copy(me = me) }
        val profile = repository.get("/v1/me/profile")
        mutable.update { it.copy(profile = profile, preferences = profile.objectAt("preferences")) }
        refresh()
    }
    fun disconnectWallet(host: WalletInteractionHost) = mutation("Local wallet connection removed. Your account and existing payouts remain linked.") { repository.disconnectWallet(host) }
    fun payout(bindingId: String) = mutation("Default saved for future claims") {
        repository.post("/v1/me/payout-preferences", buildJsonObject { put("wallet_binding_id", bindingId) })
        val preferences = repository.get("/v1/me/preferences")
        mutable.update { it.copy(preferences = preferences) }
    }
    fun balances(wallet: WalletBindingDto) = mutation(null) {
        val result = repository.balances(wallet)
        mutable.update { it.copy(balances = it.balances + (wallet.walletBindingId to result)) }
    }
    fun signOut() = mutation("Signed out on this device", changesIdentity = true) {
        repository.safeSignOut(); mutable.value = ExperienceUiState()
    }
    fun amount(atomic: String, mint: String) = repository.amount(atomic, mint)
    fun history(stateId: String, more: Boolean = false) {
        viewModelScope.launch {
            try {
                val previous = mutable.value.history[stateId]
                val cursor = if (more) previous?.text("next_cursor")?.takeIf { it.isNotBlank() } ?: return@launch else null
                val params = buildMap { put("limit", "50"); cursor?.let { put("cursor", it) } }
                val result = withContext(Dispatchers.IO) { repository.get("/v1/states/$stateId/history", params, public = true) }
                val value = if (more && previous != null) JsonObject(result + ("items" to JsonArray((previous.rows() + result.rows()).distinctBy { it.text("history_id") }))) else result
                mutable.update { it.copy(history = it.history + (stateId to value), historyErrors = it.historyErrors - stateId) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { mutable.update { it.copy(historyErrors = it.historyErrors + (stateId to "History could not be loaded. Try again.")) } }
        }
    }
    fun refreshContext(refreshId: String) = mutation(null) {
        val result = repository.get("/v1/me/activity", mapOf("refresh_id" to refreshId))
        result.rows().firstOrNull()?.let { row -> mutable.update { it.copy(refreshDetails = it.refreshDetails + (refreshId to row)) } }
    }
    fun proof(stateId: String) = mutation(null) {
        val result = repository.get("/v1/me/states/$stateId/proof")
        mutable.update { it.copy(privateProof = result) }
    }
    fun dismissProof() { mutable.update { it.copy(privateProof = null) } }
    fun dismissMessage() { mutable.update { it.copy(error = null, notice = null) } }

    private fun mutation(message: String?, changesIdentity: Boolean = false, block: suspend () -> Unit) {
        if (mutable.value.saving) return
        refreshJob?.cancel()
        identityMutation = changesIdentity
        mutable.update { it.copy(saving = true, error = null, notice = null) }
        mutationJob = viewModelScope.launch {
            try { withContext(Dispatchers.IO) { accountMutex.withLock { block() } }; mutable.update { it.copy(notice = message) } }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { mutable.update { if (error is com.sagarsystemslab.nownetwork.network.ApiFailure.AuthExpired) ExperienceUiState(error = safeMessage(error)) else it.copy(error = safeMessage(error)) } }
            finally { identityMutation = false; mutable.update { it.copy(saving = false) } }
        }
    }
    private fun safeMessage(error: Exception): String = when (error) {
        is java.io.IOException -> "Connection unavailable. Your saved work is preserved. Try again."
        else -> error.message?.take(240) ?: "This action could not be completed. Try again."
    }
}
