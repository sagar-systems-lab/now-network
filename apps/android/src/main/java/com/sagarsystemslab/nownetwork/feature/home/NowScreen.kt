package com.sagarsystemslab.nownetwork.feature.home

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sagarsystemslab.nownetwork.designsystem.*
import com.sagarsystemslab.nownetwork.feature.common.*
import com.sagarsystemslab.nownetwork.feature.state.*
import com.sagarsystemslab.nownetwork.model.GeoCenter

@Composable
fun NowScreen(
    uiState: HomeUiState,
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    serverNowMillis: () -> Long,
    onRefresh: () -> Unit,
    onStateClick: (String) -> Unit,
    onEarnClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onBrowseAreas: () -> Unit = onSettingsClick,
    onNotifications: () -> Unit = onSettingsClick,
    onProfile: () -> Unit = onSettingsClick,
    onFundState: (String) -> Unit = onStateClick,
    center: GeoCenter? = null,
    unread: Int = 0,
    onSearchArea: ((GeoCenter) -> Unit)? = null,
    onLoadMore: () -> Unit = {},
    onSearch: (String) -> Unit = {},
    onFreshness: (String) -> Unit = {},
) {
    val nowMillis = rememberVisibleServerTime(serverNowMillis)
    var search by rememberSaveable(uiState.search) { mutableStateOf(uiState.search) }
    val states = uiState.states
    val candidate = uiState.states.firstOrNull { it.freshnessAt(nowMillis) == FreshnessKind.STALE && !it.conflictActive }
    val duration = nowMotionDuration(rememberNowMotionEnabled(), 220)
    LazyColumn(Modifier.testTag("screen-now"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ExperienceHeader("NOW", "Live states near you", uiState.areaLabel, onBrowseAreas, onNotifications, onProfile, unread) }
        item { LiveMapCard(center, states.mapNotNull { s -> s.location?.center?.let { LiveMapPin(s.stateId, s.title, it, s.freshnessAt(nowMillis).name) } }, onStateClick, onSearchArea = onSearchArea) }
        item { SyncStrip(uiState.refreshing, uiState.states.size, uiState.notice != BrowseNotice.NONE, onRefresh) }
        item {
            NowGlassCard(Modifier.testTag("home-ask-refresh"), emphasized = true) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Search, null, Modifier.size(38.dp), tint = NowColors.Blue600)
                    Column(Modifier.weight(1f)) {
                        Text("What do you need to know?", style = NowType.TitleM, color = NowColors.Ink950)
                        Text("Refresh a stale nearby state with fresh proof.", style = NowType.BodyM, color = NowColors.Ink600)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Live states nearby", Modifier.weight(1f), style = NowType.LabelM, color = NowColors.LiveText)
                    Text("Refresh when stale", Modifier.weight(1f), style = NowType.LabelM, color = NowColors.InfoText)
                    Text("Earn when verified", Modifier.weight(1f), style = NowType.LabelM, color = NowColors.AgingText)
                }
                NowPrimaryButton("Choose a browse area  ›", onBrowseAreas, Modifier.fillMaxWidth().testTag("NOW-A01"))
            }
        }
        if (uiState.notice != BrowseNotice.NONE) item { BrowseNoticeCard(uiState.notice, uiState.states.isNotEmpty()) }
        item {
            MetricStrip(listOf(
                (uiState.counts?.total?.toString() ?: "—") to "Matching nearby",
                (uiState.counts?.stale?.toString() ?: "—") to "Need proof",
                (uiState.counts?.live?.toString() ?: "—") to "Live nearby",
            ))
        }
        if (candidate != null) item {
            NowGlassCard(emphasized = true) {
                StateCard(candidate, nowMillis, { onStateClick(candidate.stateId) })
                NowPrimaryButton("Refresh state", { onFundState(candidate.stateId) }, Modifier.fillMaxWidth().testTag("NOW-REFRESH-${candidate.stateId}"))
            }
        }
        item {
            NowSectionTitle("Nearby now", "Freshness changes as observations age")
            NowTextField(search, { search = it.take(120) }, "Search this area")
            NowSecondaryButton("Search area", { onSearch(search) }, Modifier.fillMaxWidth(), enabled = !uiState.refreshing)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("all", "live", "aging", "stale", "unobserved", "conflict")) { value ->
                    FilterChip(uiState.freshness == value, { onFreshness(value) },
                        enabled = !uiState.refreshing,
                        label = { Text(value.replaceFirstChar { it.uppercase() }) })
                }
            }
            Text("Area totals from the last refresh. Observations keep aging.", style = NowType.BodyS, color = NowColors.Ink600)
        }
        if (uiState.refreshing && states.isEmpty()) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = NowColors.Blue600) }
        else if (states.isEmpty() && (uiState.states.isNotEmpty() || uiState.notice == BrowseNotice.NONE)) item {
            NowGlassCard {
                Text(if (uiState.search.isBlank() && uiState.freshness == "all") "No nearby states yet" else "No states match these filters", style = NowType.TitleM, color = NowColors.Ink950)
                Text("Choose an area with coverage or check again for new observations.", style = NowType.BodyM, color = NowColors.Ink600)
                NowSecondaryButton("Browse areas", onBrowseAreas, Modifier.fillMaxWidth())
            }
        }
        else items(states, key = { it.stateId }) { state ->
            StateCard(state, nowMillis, { onStateClick(state.stateId) }, Modifier.animateItem(tween(duration), tween(duration), tween(duration)))
        }
        if (uiState.moreFailed) item { NowNotice("More states could not load. Your existing results are still available.") }
        if (uiState.nextCursor != null) item {
            NowSecondaryButton(if (uiState.loadingMore) "Loading more…" else if (uiState.moreFailed) "Retry more states" else "Load more nearby states", onLoadMore, Modifier.fillMaxWidth(), enabled = !uiState.refreshing && !uiState.loadingMore)
        }
        item {
            NowGlassCard(Modifier.testTag("home-earn-entry")) {
                ExperienceRow("Earn nearby", "Refresh real-world states nearby and earn when verified.", Icons.Outlined.WorkOutline, onEarnClick)
            }
        }
    }
}
