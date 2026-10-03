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
) {
    val nowMillis = rememberVisibleServerTime(serverNowMillis)
    var filter by rememberSaveable { mutableStateOf("All") }
    var search by rememberSaveable { mutableStateOf("") }
    val states = uiState.states.filter { (filter == "All" || it.freshnessAt(nowMillis).name == filter.uppercase()) && (it.title.contains(search, ignoreCase = true) || it.question.contains(search, ignoreCase = true)) }
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
            val known = uiState.states.isNotEmpty() || (!uiState.refreshing && uiState.notice == BrowseNotice.NONE)
            MetricStrip(listOf((if (known) uiState.states.size.toString() else "—") to "States shown", (if (known) uiState.states.count { it.freshnessAt(nowMillis) == FreshnessKind.STALE }.toString() else "—") to "Stale shown", (if (known) uiState.states.count { it.freshnessAt(nowMillis) == FreshnessKind.LIVE }.toString() else "—") to "Live shown"))
        }
        if (candidate != null) item {
            NowGlassCard(emphasized = true) {
                StateCard(candidate, nowMillis, { onStateClick(candidate.stateId) })
                NowPrimaryButton("Refresh state", { onFundState(candidate.stateId) }, Modifier.fillMaxWidth().testTag("NOW-REFRESH-${candidate.stateId}"))
            }
        }
        item {
            NowSectionTitle("Nearby now", "Freshness changes as observations age")
            NowTextField(search, { search = it }, "Search loaded states")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Live", "Aging", "Stale").forEach { value -> FilterChip(filter == value, { filter = value }, label = { Text(value) }) }
            }
        }
        if (uiState.refreshing && states.isEmpty()) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = NowColors.Blue600) }
        else if (states.isEmpty() && (uiState.states.isNotEmpty() || uiState.notice == BrowseNotice.NONE)) item {
            NowGlassCard {
                Text(if (uiState.states.isEmpty()) "No nearby states yet" else "No states match these filters", style = NowType.TitleM, color = NowColors.Ink950)
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
