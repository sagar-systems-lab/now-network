package com.sagarsystemslab.nownetwork.feature.earn

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sagarsystemslab.nownetwork.auth.AuthGatewayException
import com.sagarsystemslab.nownetwork.config.BrowseAreaConfig
import com.sagarsystemslab.nownetwork.config.PublicRuntimeConfig
import com.sagarsystemslab.nownetwork.config.RewardDisplayConfig
import com.sagarsystemslab.nownetwork.model.OpportunitySummary
import com.sagarsystemslab.nownetwork.model.estimatedPayoutAtomic
import com.sagarsystemslab.nownetwork.network.ApiFailure
import com.sagarsystemslab.nownetwork.network.NearbyOpportunityQuery
import com.sagarsystemslab.nownetwork.repository.OpportunityRepository
import com.sagarsystemslab.nownetwork.repository.ServerClock
import com.sagarsystemslab.nownetwork.repository.SessionBootstrapState
import com.sagarsystemslab.nownetwork.repository.SessionRepository
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

enum class EarnNotice {
    NONE,
    AREA_REQUIRED,
    AUTH_REQUIRED,
    NETWORK_UNAVAILABLE,
    SERVER_UNAVAILABLE,
    DATA_UNAVAILABLE,
}

data class EarnUiState(
    val areaLabel: String,
    val opportunities: List<OpportunitySummary> = emptyList(),
    val refreshing: Boolean = false,
    val notice: EarnNotice = EarnNotice.NONE,
    val nextCursor: String? = null,
    val loadingMore: Boolean = false,
    val moreFailed: Boolean = false,
)

@HiltViewModel
class EarnViewModel @Inject constructor(
    private val repository: Lazy<OpportunityRepository>,
    private val initialBrowseArea: BrowseAreaConfig,
    private val browseContext: com.sagarsystemslab.nownetwork.experience.BrowseContextStore,
    private val runtimeConfig: PublicRuntimeConfig,
    private val rewardConfig: RewardDisplayConfig,
    private val serverClock: ServerClock,
    private val sessionRepository: Lazy<SessionRepository>,
) : ViewModel() {
    private val browseArea: BrowseAreaConfig get() = browseContext.state.value
    private var areaVersion = 0
    private var refreshJob: kotlinx.coroutines.Job? = null
    private var pageJob: kotlinx.coroutines.Job? = null
    private val mutableState = MutableStateFlow(
        EarnUiState(
            areaLabel = browseArea.label.ifBlank { "Browse area" },
            notice = configurationNotice(),
        ),
    )
    val state: StateFlow<EarnUiState> = mutableState.asStateFlow()

    private var activeSnapshotIds: Set<String>? = emptySet()
    private var actorId: String? = null
    private var sessionReady = false
    private var initialRefreshStarted = false

    init {
        viewModelScope.launch {
            browseContext.state.collectLatest { area ->
                areaVersion++
                refreshJob?.cancel()
                pageJob?.cancel()
                activeSnapshotIds = actorId?.let { browseContext.snapshot("opportunities", it, area) }.orEmpty()
                val cached = withContext(Dispatchers.IO) { repository.get().observeCached().first() }.filter { it.refreshId in activeSnapshotIds.orEmpty() }
                mutableState.update { it.copy(areaLabel = area.label.ifBlank { "Browse area" }, opportunities = rank(cached), nextCursor = null, loadingMore = false, moreFailed = false) }
                refresh()
            }
        }

        viewModelScope.launch {
            val opportunityRepository = withContext(Dispatchers.IO) { repository.get() }
            opportunityRepository.observeCached()
                .flowOn(Dispatchers.IO)
                .collectLatest { cached ->
                    val visible = activeSnapshotIds?.let { ids ->
                        cached.filter { it.refreshId in ids }
                    } ?: cached

                    mutableState.update { current ->
                        if (current.opportunities.any { !it.cachedOnly }) {
                            current
                        } else {
                            current.copy(opportunities = rank(visible))
                        }
                    }
                }
        }

        if (
            runtimeConfig.apiConfigured &&
            runtimeConfig.authConfigured
        ) {
            mutableState.update { it.copy(notice = EarnNotice.AUTH_REQUIRED) }

            viewModelScope.launch {
                val sessions = withContext(Dispatchers.IO) { sessionRepository.get() }
                sessions.state.collectLatest { sessionState ->
                    when (sessionState) {
                        is SessionBootstrapState.Ready -> {
                            if (actorId != sessionState.actorId) {
                                refreshJob?.cancel(); pageJob?.cancel(); areaVersion++; initialRefreshStarted = false
                                actorId = sessionState.actorId
                                activeSnapshotIds = browseContext.snapshot("opportunities", actorId)
                                val cached = withContext(Dispatchers.IO) { repository.get().observeCached().first() }.filter { it.refreshId in activeSnapshotIds.orEmpty() }
                                mutableState.update { it.copy(opportunities = rank(cached), nextCursor = null, loadingMore = false, moreFailed = false) }
                            }
                            sessionReady = true
                            mutableState.update { it.copy(notice = EarnNotice.NONE) }
                            if (!initialRefreshStarted) {
                                initialRefreshStarted = true
                                refresh()
                            }
                        }

                        is SessionBootstrapState.Unavailable -> {
                            sessionReady = false
                            refreshJob?.cancel(); pageJob?.cancel(); areaVersion++
                            mutableState.update {
                                it.copy(
                                    refreshing = false, loadingMore = false, nextCursor = null,
                                    opportunities = it.opportunities.map { item -> item.copy(cachedOnly = true) },
                                    notice = when (sessionState.reason) {
                                        SessionBootstrapState.Unavailable.Reason.NETWORK ->
                                            EarnNotice.NETWORK_UNAVAILABLE
                                        SessionBootstrapState.Unavailable.Reason.SERVER ->
                                            EarnNotice.SERVER_UNAVAILABLE
                                        else -> EarnNotice.AUTH_REQUIRED
                                    },
                                )
                            }
                        }

                        SessionBootstrapState.Bootstrapping,
                        SessionBootstrapState.Idle -> {
                            sessionReady = false; initialRefreshStarted = false; actorId = null
                            refreshJob?.cancel(); pageJob?.cancel(); areaVersion++; activeSnapshotIds = emptySet()
                            mutableState.update { it.copy(notice = EarnNotice.AUTH_REQUIRED, opportunities = emptyList(), refreshing = false, nextCursor = null, loadingMore = false, moreFailed = false) }
                        }
                    }
                }
            }
        }
    }

    fun refresh() {
        val notice = configurationNotice()
        if (notice != EarnNotice.NONE) {
            mutableState.update { it.copy(refreshing = false, notice = notice) }
            return
        }

        if (!sessionReady) {
            mutableState.update { it.copy(refreshing = false, notice = EarnNotice.AUTH_REQUIRED) }
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    sessionRepository.get().bootstrap()
                }
            }
            return
        }

        refreshJob?.cancel()
        pageJob?.cancel()
        areaVersion++
        val requestedArea = browseArea
        val requestedVersion = areaVersion
        refreshJob = viewModelScope.launch {
            mutableState.update { it.copy(refreshing = true, notice = EarnNotice.NONE, loadingMore = false, moreFailed = false) }

            try {
                val page = withContext(Dispatchers.IO) {
                    repository.get().refreshNearby(
                        NearbyOpportunityQuery(
                            latitude = requireNotNull(requestedArea.latitude),
                            longitude = requireNotNull(requestedArea.longitude),
                            radiusMeters = requestedArea.radiusMeters,
                        ),
                    )
                }
                if (requestedVersion != areaVersion) return@launch
                activeSnapshotIds = page.items.mapTo(linkedSetOf()) { it.refreshId }
                browseContext.saveSnapshot("opportunities", activeSnapshotIds.orEmpty(), actorId, requestedArea)
                mutableState.update {
                    it.copy(
                        opportunities = rank(page.items),
                        refreshing = false,
                        notice = EarnNotice.NONE,
                        nextCursor = page.nextCursor,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (requestedVersion != areaVersion) return@launch
                mutableState.update {
                    it.copy(
                        refreshing = false,
                        notice = error.toNotice(),
                        opportunities = it.opportunities.map { item -> item.copy(cachedOnly = true) },
                    )
                }
            }
        }
    }

    fun loadMore() {
        val current = mutableState.value
        val cursor = current.nextCursor ?: return
        if (current.refreshing || current.loadingMore || !sessionReady || configurationNotice() != EarnNotice.NONE) return
        val requestedArea = browseArea
        val requestedVersion = areaVersion
        val requestedActor = actorId
        mutableState.update { it.copy(loadingMore = true, moreFailed = false) }
        pageJob = viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    repository.get().refreshNearby(NearbyOpportunityQuery(
                        latitude = requireNotNull(requestedArea.latitude),
                        longitude = requireNotNull(requestedArea.longitude),
                        radiusMeters = requestedArea.radiusMeters,
                        cursor = cursor,
                    ))
                }
                if (requestedVersion != areaVersion || requestedActor != actorId) return@launch
                activeSnapshotIds = activeSnapshotIds.orEmpty() + page.items.map { it.refreshId }
                browseContext.saveSnapshot("opportunities", activeSnapshotIds.orEmpty(), requestedActor, requestedArea)
                mutableState.update { state -> state.copy(
                    opportunities = rank((state.opportunities + page.items).associateBy { it.refreshId }.values.toList()),
                    nextCursor = page.nextCursor?.takeUnless { it == cursor },
                    loadingMore = false,
                    moreFailed = false,
                ) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                if (requestedVersion == areaVersion) mutableState.update { it.copy(loadingMore = false, moreFailed = true) }
            }
        }
    }

    fun serverNowMillis(): Long = serverClock.nowMillis()

    fun rewardText(item: OpportunitySummary): String =
        formatReward(
            atomic = item.estimatedPayoutAtomic() ?: item.rewardAtomic,
            mint = item.rewardMint,
            config = rewardConfig,
        )

    private fun configurationNotice(): EarnNotice =
        when {
            !browseArea.configured -> EarnNotice.AREA_REQUIRED
            !runtimeConfig.apiConfigured -> EarnNotice.NETWORK_UNAVAILABLE
            !runtimeConfig.authConfigured -> EarnNotice.AUTH_REQUIRED
            else -> EarnNotice.NONE
        }

    private fun rank(items: List<OpportunitySummary>): List<OpportunitySummary> =
        items.sortedWith(compareBy<OpportunitySummary> { it.distanceMeters ?: Double.MAX_VALUE }.thenBy { it.refreshId })

    private fun Exception.toNotice(): EarnNotice =
        when (this) {
            is AuthGatewayException.Network,
            is ApiFailure.NetworkUnavailable,
            is ApiFailure.Timeout,
            is ApiFailure.RateLimited -> EarnNotice.NETWORK_UNAVAILABLE

            is AuthGatewayException.Configuration,
            is AuthGatewayException.Rejected,
            is ApiFailure.AuthExpired -> EarnNotice.AUTH_REQUIRED
            is ApiFailure.ServerFailure -> EarnNotice.SERVER_UNAVAILABLE
            else -> EarnNotice.DATA_UNAVAILABLE
        }
}
