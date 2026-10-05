package com.sagarsystemslab.nownetwork.feature.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sagarsystemslab.nownetwork.config.BrowseAreaConfig
import com.sagarsystemslab.nownetwork.config.PublicRuntimeConfig
import com.sagarsystemslab.nownetwork.model.StateDetail
import com.sagarsystemslab.nownetwork.model.StateSummary
import com.sagarsystemslab.nownetwork.network.ApiFailure
import com.sagarsystemslab.nownetwork.network.NearbyStateQuery
import com.sagarsystemslab.nownetwork.repository.ServerClock
import com.sagarsystemslab.nownetwork.repository.StateRepository
import com.sagarsystemslab.nownetwork.realtime.NowRealtimeGateway
import com.sagarsystemslab.nownetwork.realtime.NowRealtimeSignal
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class BrowseNotice {
    NONE,
    AREA_REQUIRED,
    CONFIGURATION_REQUIRED,
    NETWORK_UNAVAILABLE,
    SERVER_UNAVAILABLE,
    DATA_UNAVAILABLE,
}

data class HomeUiState(
    val areaLabel: String,
    val states: List<StateSummary> = emptyList(),
    val refreshing: Boolean = false,
    val notice: BrowseNotice = BrowseNotice.NONE,
    val nextCursor: String? = null,
    val loadingMore: Boolean = false,
    val moreFailed: Boolean = false,
    val search: String = "",
    val freshness: String = "all",
    val counts: com.sagarsystemslab.nownetwork.model.NearbyStateCounts? = null,
)

data class StateDetailUiState(
    val stateId: String? = null,
    val cachedSummary: StateSummary? = null,
    val detail: StateDetail? = null,
    val loading: Boolean = false,
    val notice: BrowseNotice = BrowseNotice.NONE,
)

@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val repository: Lazy<StateRepository>,
    private val initialBrowseArea: BrowseAreaConfig,
    private val browseContext: com.sagarsystemslab.nownetwork.experience.BrowseContextStore,
    private val runtimeConfig: PublicRuntimeConfig,
    private val serverClock: ServerClock,
    private val realtimeGateway: Lazy<NowRealtimeGateway>,
) : ViewModel() {
    private val browseArea: BrowseAreaConfig get() = browseContext.state.value
    private var areaVersion = 0
    private var refreshJob: kotlinx.coroutines.Job? = null
    private var pageJob: kotlinx.coroutines.Job? = null
    private val mutableHomeState = MutableStateFlow(
        HomeUiState(
            areaLabel = browseArea.label.ifBlank { "Browse area" },
            notice = if (browseArea.configured) {
                BrowseNotice.NONE
            } else {
                BrowseNotice.AREA_REQUIRED
            },
        ),
    )
    val homeState: StateFlow<HomeUiState> = mutableHomeState.asStateFlow()

    private val mutableDetailState = MutableStateFlow(StateDetailUiState())
    val detailState: StateFlow<StateDetailUiState> = mutableDetailState.asStateFlow()

    private fun snapshotKind(search: String = mutableHomeState.value.search, freshness: String = mutableHomeState.value.freshness) = "states:$freshness:$search"

    private var activeSnapshotIds: Set<String> = emptySet()
    private val realtimeResyncPolicy = RealtimeResyncPolicy()
    private var foregroundCount = 0

    init {
        viewModelScope.launch {
            browseContext.state.collectLatest { area ->
                areaVersion++
                refreshJob?.cancel()
                pageJob?.cancel()
                activeSnapshotIds = browseContext.snapshot(snapshotKind(), area = area)
                val cached = withContext(Dispatchers.IO) { repository.get().observeCachedStates().first() }.filter { it.stateId in activeSnapshotIds.orEmpty() }
                mutableHomeState.update { it.copy(areaLabel = area.label.ifBlank { "Browse area" }, states = cached, nextCursor = null, loadingMore = false, moreFailed = false, counts = null) }
                refreshHome()
            }
        }

        viewModelScope.launch {
            val stateRepository = withContext(Dispatchers.IO) { repository.get() }
            stateRepository.observeCachedStates()
                .flowOn(Dispatchers.IO)
                .collectLatest { states ->
                    val visible = states.filter { it.stateId in activeSnapshotIds }

                    mutableHomeState.update { current ->
                        current.copy(states = visible.sortedWith(stateOrder))
                    }

                    val selectedId = mutableDetailState.value.stateId
                    if (selectedId != null) {
                        mutableDetailState.update { current ->
                            current.copy(
                                cachedSummary = states.firstOrNull { it.stateId == selectedId }
                                    ?: current.cachedSummary,
                            )
                        }
                    }
                }
        }

        viewModelScope.launch {
            val gateway = withContext(Dispatchers.IO) { realtimeGateway.get() }
            gateway.signals()
                .flowOn(Dispatchers.IO)
                .collect { signal ->
                when (signal) {
                    NowRealtimeSignal.Connected -> {
                        if (realtimeResyncPolicy.onConnected()) {
                            refreshHome()
                        }
                    }

                    NowRealtimeSignal.Disconnected -> {
                        realtimeResyncPolicy.onDisconnected()
                    }

                    NowRealtimeSignal.Unavailable -> Unit

                    is NowRealtimeSignal.Event -> {
                        handleRealtimeEvent(signal)
                    }
                }
            }
        }

        if (browseArea.configured && runtimeConfig.apiConfigured) {
            refreshHome()
        }
    }

    fun onForeground() {
        foregroundCount += 1
        if (
            foregroundCount > 1 &&
            browseArea.configured &&
            runtimeConfig.apiConfigured
        ) {
            refreshHome()
        }
    }

    fun refreshHome() = refreshQuery(mutableHomeState.value.search, mutableHomeState.value.freshness)

    fun search(value: String) = refreshQuery(value.trim().take(120), mutableHomeState.value.freshness)

    fun selectFreshness(value: String) = refreshQuery(mutableHomeState.value.search, value)

    private fun refreshQuery(search: String, freshness: String) {
        if (!browseArea.configured) {
            mutableHomeState.update {
                it.copy(refreshing = false, notice = BrowseNotice.AREA_REQUIRED)
            }
            return
        }

        if (!runtimeConfig.apiConfigured) {
            mutableHomeState.update {
                it.copy(refreshing = false, notice = BrowseNotice.CONFIGURATION_REQUIRED)
            }
            return
        }

        refreshJob?.cancel()
        pageJob?.cancel()
        areaVersion++
        val requestedArea = browseArea
        val requestedVersion = areaVersion
        refreshJob = viewModelScope.launch {
            mutableHomeState.update { it.copy(refreshing = true, notice = BrowseNotice.NONE, loadingMore = false, moreFailed = false) }

            try {
                val page = withContext(Dispatchers.IO) {
                    repository.get().refreshNearby(
                        NearbyStateQuery(
                            latitude = requireNotNull(requestedArea.latitude),
                            longitude = requireNotNull(requestedArea.longitude),
                            radiusMeters = requestedArea.radiusMeters,
                            search = search,
                            freshness = freshness,
                        ),
                    )
                }
                if (requestedVersion != areaVersion) return@launch
                activeSnapshotIds = page.items.mapTo(linkedSetOf()) { it.stateId }
                browseContext.saveSnapshot(snapshotKind(search, freshness), activeSnapshotIds.orEmpty(), area = requestedArea)
                mutableHomeState.update {
                    it.copy(
                        states = page.items.sortedWith(stateOrder),
                        search = search,
                        freshness = freshness,
                        counts = page.counts,
                        refreshing = false,
                        notice = BrowseNotice.NONE,
                        nextCursor = page.nextCursor,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (requestedVersion != areaVersion) return@launch
                mutableHomeState.update {
                    it.copy(
                        refreshing = false,
                        notice = error.toBrowseNotice(),
                    )
                }
            }
        }
    }

    fun loadMore() {
        val current = mutableHomeState.value
        val cursor = current.nextCursor ?: return
        if (current.refreshing || current.loadingMore || !browseArea.configured || !runtimeConfig.apiConfigured) return
        val requestedArea = browseArea
        val requestedVersion = areaVersion
        mutableHomeState.update { it.copy(loadingMore = true, moreFailed = false) }
        pageJob = viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    repository.get().refreshNearby(NearbyStateQuery(
                        latitude = requireNotNull(requestedArea.latitude),
                        longitude = requireNotNull(requestedArea.longitude),
                        radiusMeters = requestedArea.radiusMeters,
                        cursor = cursor,
                        search = current.search,
                        freshness = current.freshness,
                    ))
                }
                if (requestedVersion != areaVersion) return@launch
                activeSnapshotIds = activeSnapshotIds + page.items.map { it.stateId }
                browseContext.saveSnapshot(snapshotKind(current.search, current.freshness), activeSnapshotIds, area = requestedArea)
                mutableHomeState.update { state -> state.copy(
                    states = (state.states + page.items).associateBy { it.stateId }.values.sortedWith(stateOrder),
                    nextCursor = page.nextCursor?.takeUnless { it == cursor },
                    loadingMore = false,
                    moreFailed = false,
                ) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                if (requestedVersion == areaVersion) mutableHomeState.update { it.copy(loadingMore = false, moreFailed = true) }
            }
        }
    }

    fun openState(stateId: String) {
        val cached = mutableHomeState.value.states.firstOrNull { it.stateId == stateId }

        mutableDetailState.value = StateDetailUiState(
            stateId = stateId,
            cachedSummary = cached,
            loading = runtimeConfig.apiConfigured,
            notice = if (runtimeConfig.apiConfigured) {
                BrowseNotice.NONE
            } else {
                BrowseNotice.CONFIGURATION_REQUIRED
            },
        )

        if (!runtimeConfig.apiConfigured) {
            return
        }

        viewModelScope.launch {
            try {
                val detail = withContext(Dispatchers.IO) {
                    repository.get().getState(stateId)
                }
                if (mutableDetailState.value.stateId != stateId) return@launch
                mutableDetailState.update {
                    it.copy(
                        detail = detail,
                        loading = false,
                        notice = BrowseNotice.NONE,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableDetailState.update {
                    it.copy(
                        loading = false,
                        notice = error.toBrowseNotice(),
                    )
                }
            }
        }
    }

    private suspend fun handleRealtimeEvent(signal: NowRealtimeSignal.Event) {
        if (
            signal.eventType != "STATE_UPDATED" ||
            signal.entityType != "state"
        ) {
            return
        }

        val selectedId = mutableDetailState.value.stateId
        val visibleIds = activeSnapshotIds
        val relevant =
            signal.entityId == selectedId ||
                (visibleIds != null && signal.entityId in visibleIds)

        if (!relevant) {
            return
        }

        try {
            val detail = withContext(Dispatchers.IO) {
                repository.get().reconcileRealtimeState(
                    stateId = signal.entityId,
                    incomingRevision = signal.entityRevision,
                )
            }

            if (detail != null && signal.entityId == selectedId) {
                mutableDetailState.update {
                    it.copy(
                        detail = detail,
                        loading = false,
                        notice = BrowseNotice.NONE,
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (signal.entityId == selectedId) {
                mutableDetailState.update {
                    it.copy(
                        loading = false,
                        notice = error.toBrowseNotice(),
                    )
                }
            } else {
                mutableHomeState.update {
                    it.copy(notice = error.toBrowseNotice())
                }
            }
        }
    }

    fun retryState() {
        mutableDetailState.value.stateId?.let(::openState)
    }

    fun serverNowMillis(): Long = serverClock.nowMillis()

    private fun Exception.toBrowseNotice(): BrowseNotice =
        when (this) {
            is ApiFailure.Configuration -> BrowseNotice.CONFIGURATION_REQUIRED
            is ApiFailure.NetworkUnavailable,
            is ApiFailure.Timeout,
            is ApiFailure.RateLimited -> BrowseNotice.NETWORK_UNAVAILABLE

            is ApiFailure.ServerFailure -> BrowseNotice.SERVER_UNAVAILABLE

            else -> BrowseNotice.DATA_UNAVAILABLE
        }

    private companion object {
        val stateOrder = compareBy<StateSummary> { it.distanceMeters ?: Double.MAX_VALUE }.thenBy { it.stateId }
    }
}
