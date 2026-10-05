package com.sagarsystemslab.nownetwork.feature.home

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.sagarsystemslab.nownetwork.designsystem.*
import com.sagarsystemslab.nownetwork.feature.common.*
import com.sagarsystemslab.nownetwork.feature.state.*
import com.sagarsystemslab.nownetwork.model.GeoCenter

@OptIn(ExperimentalMaterial3Api::class)
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
    onAsk: () -> Unit = onBrowseAreas,
    center: GeoCenter? = null,
    unread: Int = 0,
    onSearchArea: ((GeoCenter) -> Unit)? = null,
    onLocateArea: ((GeoCenter) -> Unit)? = onSearchArea,
    onLoadMore: () -> Unit = {},
    onSearch: (String) -> Unit = {},
    onFreshness: (String) -> Unit = {},
) {
    val nowMillis = rememberVisibleServerTime(serverNowMillis)
    var search by rememberSaveable(uiState.search) { mutableStateOf(uiState.search) }
    var showNearby by rememberSaveable { mutableStateOf(false) }
    val states = uiState.states
    val candidate = uiState.states.firstOrNull { it.freshnessAt(nowMillis) == FreshnessKind.STALE && !it.conflictActive }
    val duration = nowMotionDuration(rememberNowMotionEnabled(), 220)
    val nearbyCardWidth = if (LocalDensity.current.fontScale >= 1.5f) 280.dp else 200.dp
    LazyColumn(Modifier.testTag("screen-now"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ExperienceHeader("NOW", "Live states near you", uiState.areaLabel, onBrowseAreas, onNotifications, onProfile, unread) }
        item { LiveMapCard(center, states.mapNotNull { s -> s.location?.center?.let { LiveMapPin(s.stateId, s.title, it, s.freshnessAt(nowMillis).name) } }, onStateClick, onSearchArea = onSearchArea, onLocateArea = onLocateArea) }
        item { SyncStrip(uiState.refreshing, uiState.states.size, uiState.notice != BrowseNotice.NONE, onRefresh,
            unavailableMessage = when(uiState.notice) {
                BrowseNotice.CONFIGURATION_REQUIRED -> "Install the connected APK to load live data."
                BrowseNotice.AREA_REQUIRED -> "Choose a browse area to see nearby places."
                BrowseNotice.SERVER_UNAVAILABLE -> "The service is temporarily unavailable. Try again shortly."
                BrowseNotice.DATA_UNAVAILABLE -> "This area's data could not load. Tap to retry."
                else -> null
            }) }
        item {
            NowGlassCard(Modifier.testTag("home-ask-refresh"), emphasized = true, contentPadding = 12.dp, spacing = 8.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    LuminousIcon(Icons.Outlined.Search, Modifier.size(48.dp))
                    Column(Modifier.weight(1f)) {
                        Text("What do you need to know?", style = NowType.TitleS, color = NowColors.Ink950)
                        Text("Choose a place and ask for fresh, on-site proof.", style = NowType.BodyM, color = NowColors.Ink600)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Triple(Icons.Outlined.LocationOn, "Live states nearby", NowColors.LiveText), Triple(Icons.Outlined.Schedule, "Refresh when stale", NowColors.Blue600), Triple(Icons.Outlined.Payments, "Earn when verified", NowColors.AgingText)).forEach { (icon, label, color) ->
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                            LuminousIcon(icon, Modifier.size(26.dp), color)
                            Text(label, style = NowType.LabelM, color = NowColors.Ink700)
                        }
                    }
                }
                NowPrimaryButton("Ask about a place  ›", onAsk, Modifier.fillMaxWidth().testTag("NOW-A01"))
                NowSecondaryButton("Browse areas", onBrowseAreas, Modifier.fillMaxWidth())
            }
        }
        if (uiState.notice != BrowseNotice.NONE) item { BrowseNoticeCard(uiState.notice, uiState.states.isNotEmpty()) }
        item {
            MetricStrip(listOf(
                (uiState.counts?.total?.toString() ?: "—") to "Matching nearby",
                (uiState.counts?.stale?.toString() ?: "—") to "Need proof",
                (uiState.counts?.live?.toString() ?: "—") to "Live nearby",
            )) { index -> onFreshness(if (index == 1) "needs_proof" else if (index == 2) "live" else "all"); showNearby = true }
        }
        if (candidate != null) item {
            StateCard(candidate, nowMillis, { onStateClick(candidate.stateId) }, onRefresh = { onFundState(candidate.stateId) })
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Nearby now", Modifier.weight(1f), style = NowType.TitleM, color = NowColors.Ink950)
                TextButton({ showNearby = true }) { Text("See all"); Icon(Icons.Outlined.ChevronRight, null) }
            }
            if (uiState.search.isNotBlank() || uiState.freshness != "all") Text("Filtered area · ${uiState.search.ifBlank { uiState.freshness.replace('_', ' ') }}", style = NowType.BodyS, color = NowColors.Ink600)
        }
        if (uiState.refreshing && states.isEmpty()) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = NowColors.Blue600) }
        else if (states.isEmpty()) item {
            NowGlassCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ProofArtwork(false, Modifier.size(86.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (uiState.notice != BrowseNotice.NONE) "Explore a live area" else if (uiState.search.isBlank() && uiState.freshness == "all") "No nearby states yet" else "No matching states", style = NowType.TitleM, color = NowColors.Ink950)
                        Text("Choose an area with coverage to discover fresh observations.", style = NowType.BodyS, color = NowColors.Ink600)
                    }
                }
                NowSecondaryButton("Browse areas", onBrowseAreas, Modifier.fillMaxWidth())
            }
        }
        else item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(states, key = { it.stateId }) { state ->
                    NearbyStateCard(state, nowMillis, { onStateClick(state.stateId) }, Modifier.width(nearbyCardWidth).animateItem(tween(duration), tween(duration), tween(duration)))
                }
            }
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
    if (showNearby) NearbyListSurface(onDismiss = { showNearby = false }) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(.88f).testTag("nearby-state-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                NowSectionTitle("Nearby states", uiState.areaLabel)
                NowTextField(search, { search = it.take(120) }, "Search this area")
                NowSecondaryButton("Search area", { onSearch(search) }, Modifier.fillMaxWidth(), enabled = !uiState.refreshing)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("all", "live", "aging", "stale", "needs_proof", "unobserved", "conflict")) { value ->
                        FilterChip(uiState.freshness == value, { onFreshness(value) }, enabled = !uiState.refreshing,
                            label = { Text(value.replace('_', ' ').replaceFirstChar { it.uppercase() }) })
                    }
                }
                Text("${uiState.counts?.total?.toString() ?: "—"} matching nearby · ${states.size} shown. Area totals are from the last refresh.", style = NowType.BodyS, color = NowColors.Ink600)
            }
            if (uiState.refreshing) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (uiState.notice != BrowseNotice.NONE) item { BrowseNoticeCard(uiState.notice, states.isNotEmpty()) }
            if (states.isEmpty() && !uiState.refreshing) item { NowNotice("No states match this area and filter.") }
            items(states, key = { it.stateId }) { state -> StateCard(state, nowMillis, { showNearby = false; onStateClick(state.stateId) }) }
            if (uiState.moreFailed) item { NowNotice("More states could not load. Your current results are still available.") }
            if (uiState.nextCursor != null) item { NowSecondaryButton(if (uiState.loadingMore) "Loading more…" else if (uiState.moreFailed) "Retry more states" else "Load more states", onLoadMore, Modifier.fillMaxWidth(), enabled = !uiState.loadingMore && !uiState.refreshing) }
            item { NowSecondaryButton("Done", { showNearby = false }, Modifier.fillMaxWidth()) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NearbyListSurface(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    // The app's Reduce Motion setting also applies to this Material sheet.
    if (!LocalNowMotionAllowed.current || !android.animation.ValueAnimator.areAnimatorsEnabled()) {
        androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss,
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxWidth().padding(8.dp).imePadding(), shape = NowShapes.extraLarge, color = NowColors.SurfaceCanvas) { content() }
        }
    } else {
        ModalBottomSheet(onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = NowColors.SurfaceCanvas) { content() }
    }
}
