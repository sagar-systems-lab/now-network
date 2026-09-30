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
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class BrowseNotice {
    NONE,
    AREA_REQUIRED,
    NETWORK_UNAVAILABLE,
    SERVER_UNAVAILABLE,
    DATA_UNAVAILABLE,
}

data class HomeUiState(
    val areaLabel: String,
    val states: List<StateSummary> = emptyList(),
    val refreshing: Boolean = false,
    val notice: BrowseNotice = BrowseNotice.NONE,
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
    private val repository: StateRepository,
    private val browseArea: BrowseAreaConfig,
    private val runtimeConfig: PublicRuntimeConfig,
    private val serverClock: ServerClock,
    private val realtimeGateway: NowRealtimeGateway,
) : ViewModel() {
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

    private var activeSnapshotIds: Set<String>? = null
    private val realtimeResyncPolicy = RealtimeResyncPolicy()
    private var foregroundCount = 0

    init {
        viewModelScope.launch {
            repository.observeCachedStates().collectLatest { states ->
                val visible = activeSnapshotIds?.let { ids ->
                    states.filter { it.stateId in ids }
                } ?: states

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
            realtimeGateway.signals().collect { signal ->
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

    fun refreshHome() {
        if (!browseArea.configured) {
            mutableHomeState.update {
                it.copy(refreshing = false, notice = BrowseNotice.AREA_REQUIRED)
            }
            return
        }

        if (!runtimeConfig.apiConfigured) {
            mutableHomeState.update {
                it.copy(refreshing = false, notice = BrowseNotice.NETWORK_UNAVAILABLE)
            }
            return
        }

        viewModelScope.launch {
            mutableHomeState.update { it.copy(refreshing = true, notice = BrowseNotice.NONE) }

            try {
                val page = repository.refreshNearby(
                    NearbyStateQuery(
                        latitude = requireNotNull(browseArea.latitude),
                        longitude = requireNotNull(browseArea.longitude),
                        radiusMeters = browseArea.radiusMeters,
                    ),
                )
                activeSnapshotIds = page.items.mapTo(linkedSetOf()) { it.stateId }
                mutableHomeState.update {
                    it.copy(
                        states = page.items.sortedWith(stateOrder),
                        refreshing = false,
                        notice = BrowseNotice.NONE,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableHomeState.update {
                    it.copy(
                        refreshing = false,
                        notice = error.toBrowseNotice(),
                    )
                }
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
                BrowseNotice.NETWORK_UNAVAILABLE
            },
        )

        if (!runtimeConfig.apiConfigured) {
            return
        }

        viewModelScope.launch {
            try {
                val detail = repository.getState(stateId)
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
            val detail = repository.reconcileRealtimeState(
                stateId = signal.entityId,
                incomingRevision = signal.entityRevision,
            )

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
            is ApiFailure.NetworkUnavailable,
            is ApiFailure.Timeout,
            is ApiFailure.RateLimited -> BrowseNotice.NETWORK_UNAVAILABLE

            is ApiFailure.ServerFailure -> BrowseNotice.SERVER_UNAVAILABLE

            else -> BrowseNotice.DATA_UNAVAILABLE
        }

    private companion object {
        val stateOrder =
            compareBy<StateSummary>(
                { freshnessRank(it) },
                { it.distanceMeters ?: Double.MAX_VALUE },
                { it.title },
            )

        fun freshnessRank(state: StateSummary): Int =
            when {
                state.conflictActive -> 0
                state.freshnessStatus == "LIVE" -> 1
                state.freshnessStatus == "AGING" -> 2
                state.freshnessStatus == "STALE" -> 3
                else -> 4
            }
    }
}
