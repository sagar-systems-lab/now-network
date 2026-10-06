package com.sagarsystemslab.nownetwork.feature.ask

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sagarsystemslab.nownetwork.evidence.EvidenceLocationProvider
import com.sagarsystemslab.nownetwork.experience.BrowseContextStore
import com.sagarsystemslab.nownetwork.experience.text
import com.sagarsystemslab.nownetwork.model.GeoCenter
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

@HiltViewModel
class AskComposerViewModel @Inject constructor(
    private val saved: SavedStateHandle,
    private val repository: AskRepository,
    private val locations: EvidenceLocationProvider,
    private val browse: BrowseContextStore,
    private val places: PlaceSearchRepository,
) : ViewModel() {
    private val initial = AskDraft(saved["ask_name"] ?: "",
        if (saved.get<Boolean>("ask_target_confirmed") == true) saved.get<Double>("ask_lat")?.let { lat -> saved.get<Double>("ask_lng")?.let { GeoCenter(lat,it) } } else null,
        saved.get<String>("ask_need")?.let { name -> AskNeed.entries.find { it.name == name } },
        saved["ask_key"] ?: UUID.randomUUID().toString(), saved["ask_question"] ?: "",
        saved["ask_address"] ?: "")
    private val mutable = MutableStateFlow(AskUiState(initial,
        if (!initial.targetReady) AskStage.TARGET else
            saved.get<String>("ask_stage")?.let { value -> AskStage.entries.find { it.name == value && (it != AskStage.PREVIEW || initial.needReady) } } ?: AskStage.TARGET,
        query = saved["ask_query"] ?: initial.name))
    val state = mutable.asStateFlow()
    private var searchJob: Job? = null
    private var searchRevision = 0
    private var origin: JsonObject? = null
    private var originElapsed = 0L

    fun open() {
        if (mutable.value.mapCenter == null) {
            val area = browse.state.value
            if (area.configured) mutable.value = mutable.value.copy(mapCenter = GeoCenter(requireNotNull(area.latitude), requireNotNull(area.longitude)))
        }
        if (!mutable.value.draft.targetReady && mutable.value.query.length >= 3) search()
    }
    private fun edit(draft: AskDraft) {
        if (mutable.value.busy) return
        val next = draft.copy(key=UUID.randomUUID().toString())
        saved["ask_name"]=next.name; saved["ask_lat"]=next.target?.latitude; saved["ask_lng"]=next.target?.longitude
        saved["ask_need"]=next.need?.name; saved["ask_key"]=next.key; saved["ask_payload"]=null
        saved["ask_question"]=next.customQuestion; saved["ask_address"]=next.displayAddress
        saved["ask_target_confirmed"]=next.targetReady
        mutable.value=mutable.value.copy(draft=next,coverage=null,notice=null,resolvedStateId=null)
    }
    fun name(value: String) {
        if (mutable.value.busy) return
        val query = value.take(160)
        if (query == mutable.value.query) return
        edit(mutable.value.draft.searching())
        saved["ask_query"] = query
        mutable.value = mutable.value.copy(query = query, suggestions = emptyList(), searchMessage = null)
        search(450)
    }
    fun search(delayMillis: Long = 0) {
        if (mutable.value.busy) return
        val revision = ++searchRevision
        searchJob?.cancel()
        val query = mutable.value.query.trim()
        mutable.value = mutable.value.copy(searching = query.length >= 3, suggestions = emptyList(), searchMessage = null)
        if (query.length < 3) return
        searchJob = viewModelScope.launch {
            try {
                delay(delayMillis)
                val results = withTimeout(10_000) { places.search(query) }
                if (searchRevision != revision) return@launch
                mutable.value = mutable.value.copy(suggestions = results,
                    searchMessage = if (results.isEmpty()) "No matching places. Add a city or street, or choose a pin on the map." else null)
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                if (searchRevision == revision) mutable.value = mutable.value.copy(searchMessage = "Search took too long. Retry or choose a map pin.")
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                if (searchRevision == revision) mutable.value = mutable.value.copy(searchMessage = "Address search is unavailable. Retry or choose a map pin.")
            } finally {
                if (searchRevision == revision) mutable.value = mutable.value.copy(searching = false)
            }
        }
    }
    fun selectPlace(place: PlaceSuggestion) {
        if (mutable.value.busy || !place.center.valid) return
        searchRevision++
        searchJob?.cancel()
        val address = place.address.ifBlank { place.name }
        edit(mutable.value.draft.copy(name = place.name, target = place.center, displayAddress = address))
        saved["ask_query"] = address
        mutable.value = mutable.value.copy(query = address, mapCenter = place.center,
            suggestions = emptyList(), searching = false, searchMessage = null)
    }
    fun target(value: GeoCenter) {
        if (!value.valid || mutable.value.busy) return
        searchRevision++
        searchJob?.cancel()
        edit(mutable.value.draft.copy(
            name = "Exact map pin",
            target = value,
            displayAddress = "",
        ))
        saved["ask_query"] = ""
        mutable.value = mutable.value.copy(
            query = "",
            mapCenter = value,
            suggestions = emptyList(),
            searching = false,
            searchMessage = null,
        )
        viewModelScope.launch {
            val place = runCatching { withTimeout(8_000) { places.reverse(value) } }.getOrNull() ?: return@launch
            val current = mutable.value.draft
            if (current.target != value || mutable.value.busy) return@launch
            val address = place.address.ifBlank { place.name }
            val next = current.copy(name = place.name, displayAddress = address)
            saved["ask_name"] = next.name
            saved["ask_address"] = next.displayAddress
            saved["ask_query"] = address
            mutable.value = mutable.value.copy(draft = next, query = address)
        }
    }
    fun need(value: AskNeed) { edit(mutable.value.draft.copy(need=value)) }
    fun question(value: String) { edit(mutable.value.draft.copy(customQuestion = value.take(200))) }
    fun stage(value: AskStage) {
        if (mutable.value.busy || value != AskStage.TARGET && !mutable.value.draft.targetReady) return
        if (value == AskStage.PREVIEW && !mutable.value.draft.needReady) return
        saved["ask_stage"]=value.name; mutable.value=mutable.value.copy(stage=value,notice=null)
    }
    fun permissionDenied() { mutable.value=mutable.value.copy(notice="Precise location is needed to exclude contributors within 100 m of you. You can still choose a remote place and continue.") }

    fun checkCoverage() {
        val target = mutable.value.draft.target ?: return
        if (mutable.value.checking || mutable.value.busy) return
        viewModelScope.launch {
            val draftKey=mutable.value.draft.key
            mutable.value=mutable.value.copy(checking=true,coverage=null,notice=null)
            try {
                val sample=withTimeout(20_000) { locations.currentSample() }
                val age=SystemClock.elapsedRealtime()-sample.observedElapsedRealtimeMs
                val accuracy=sample.accuracyMeters
                check(age in 0L..60_000L && accuracy != null && accuracy in 0.0..100.0 && sample.mockSignal != true) {
                    "Location is not precise enough. Move into open sky and try again."
                }
                val position=buildJsonObject {
                    put("lat",sample.latitude); put("lng",sample.longitude); put("accuracy_m",requireNotNull(accuracy))
                    put("captured_at",Instant.now().minusMillis(age).toString())
                }
                val result=repository.coverage(target,position)
                if(mutable.value.draft.key==draftKey) {
                    origin=position; originElapsed=sample.observedElapsedRealtimeMs
                    mutable.value=mutable.value.copy(coverage=result)
                }
            } catch(_: kotlinx.coroutines.TimeoutCancellationException) {
                mutable.value=mutable.value.copy(notice="Location timed out. Check device location and try again.")
            } catch(error: CancellationException) { throw error }
            catch(error: Exception) {
                if(mutable.value.draft.key==draftKey) mutable.value=mutable.value.copy(notice=failureMessage(error))
            } finally { mutable.value=mutable.value.copy(checking=false) }
        }
    }
    fun resolve() {
        val current=mutable.value
        if(current.busy || current.checking || !current.draft.targetReady || !current.draft.needReady) return
        viewModelScope.launch {
            mutable.value=mutable.value.copy(busy=true,notice=null)
            try {
                val stored=saved.get<String>("ask_payload")
                val payload=stored?.let { Json.parseToJsonElement(it).jsonObject } ?: buildJsonObject {
                    current.draft.payload().forEach { (key,value) -> put(key,value) }
                    if(SystemClock.elapsedRealtime()-originElapsed in 0L..55_000L) origin?.let { put("requester_location",it) }
                }.also { saved["ask_payload"]=it.toString(); saved["ask_key"]=current.draft.key }
                val result=repository.resolve(payload,current.draft.key)
                val id=result.text("state_id")
                check(id.isNotBlank()) { "The server did not return this place. Retry the same draft." }
                mutable.value=mutable.value.copy(resolvedStateId=id,
                    activeRefresh=result["active_refresh_id"] is JsonPrimitive && result["active_refresh_id"] !is JsonNull)
            } catch(error: CancellationException) { throw error }
            catch(error: Exception) {
                if (error is com.sagarsystemslab.nownetwork.network.ApiFailure.BusinessError && error.code == "LOCATION_STALE") {
                    // A rejected sample has no committed resolver operation. Network failures retain the exact payload.
                    val key=UUID.randomUUID().toString()
                    saved["ask_payload"]=null; saved["ask_key"]=key; origin=null; originElapsed=0
                    mutable.value=mutable.value.copy(draft=mutable.value.draft.copy(key=key),coverage=null,
                        notice="Location expired. Check coverage again, or continue without a coverage estimate.")
                } else mutable.value=mutable.value.copy(notice=failureMessage(error))
            }
            finally { mutable.value=mutable.value.copy(busy=false) }
        }
    }
    fun consumed() { mutable.value=mutable.value.copy(resolvedStateId=null) }
    private fun failureMessage(error: Exception): String = when(error) {
        is com.sagarsystemslab.nownetwork.auth.AuthUnavailableException,
        is com.sagarsystemslab.nownetwork.auth.AuthGatewayException.Configuration,
        is com.sagarsystemslab.nownetwork.network.ApiFailure.Configuration ->
            "This build isn't connected to NOW. Install the connected APK; your draft is saved."
        is com.sagarsystemslab.nownetwork.network.ApiFailure.BusinessError ->
            if(error.code == "NOT_FOUND") "ASK isn't available on this server yet. Your draft is saved."
            else error.message ?: "This request could not be completed. Your draft is saved."
        is com.sagarsystemslab.nownetwork.network.ApiFailure.ServerFailure ->
            "The ASK service is temporarily unavailable. Retry keeps your saved draft."
        else -> error.message ?: "Can't reach NOW right now. Your draft is saved; try again."
    }
}
