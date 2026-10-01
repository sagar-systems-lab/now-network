package com.sagarsystemslab.nownetwork.feature.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sagarsystemslab.nownetwork.designsystem.NowColors
import com.sagarsystemslab.nownetwork.designsystem.NowSpacing
import com.sagarsystemslab.nownetwork.designsystem.NowType
import com.sagarsystemslab.nownetwork.feature.state.BrowseNotice
import com.sagarsystemslab.nownetwork.feature.state.BrowseNoticeCard
import com.sagarsystemslab.nownetwork.feature.state.FreshnessKind
import com.sagarsystemslab.nownetwork.feature.state.HomeUiState
import com.sagarsystemslab.nownetwork.feature.state.StateCard
import com.sagarsystemslab.nownetwork.feature.state.freshnessAt
import com.sagarsystemslab.nownetwork.feature.state.rememberVisibleServerTime

@Composable
fun NowScreen(
    uiState: HomeUiState,
    serverNowMillis: () -> Long,
    onRefresh: () -> Unit,
    onStateClick: (String) -> Unit,
) {
    val nowMillis = rememberVisibleServerTime(serverNowMillis)

    LazyColumn(
        modifier = Modifier.testTag("screen-now"),
        contentPadding = PaddingValues(
            start = NowSpacing.PageHorizontal,
            top = NowSpacing.Space3,
            end = NowSpacing.PageHorizontal,
            bottom = NowSpacing.Space6,
        ),
        verticalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
    ) {
        item {
            HomeHeader(
                areaLabel = uiState.areaLabel,
                refreshing = uiState.refreshing,
                hasStates = uiState.states.isNotEmpty(),
                canRefresh = uiState.notice != BrowseNotice.AREA_REQUIRED,
                onRefresh = onRefresh,
            )
        }

        item {
            AskRefreshCard(
                refreshing = uiState.refreshing,
                canRefresh = uiState.notice != BrowseNotice.AREA_REQUIRED,
                onRefresh = onRefresh,
            )
        }

        if (uiState.notice != BrowseNotice.NONE) {
            item {
                BrowseNoticeCard(
                    notice = uiState.notice,
                    hasCachedContent = uiState.states.isNotEmpty(),
                )
            }
        }

        if (uiState.states.isNotEmpty()) {
            item {
                LiveNetworkSummary(
                    uiState = uiState,
                    nowMillis = nowMillis,
                )
            }
        }

        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(NowSpacing.Space1),
            ) {
                Text(
                    text = "Nearby now",
                    style = NowType.TitleS,
                    color = NowColors.Ink950,
                )
                Text(
                    text = "Verified physical states around you.",
                    style = NowType.BodyS,
                    color = NowColors.Ink500,
                )
            }
        }

        if (uiState.refreshing && uiState.states.isNotEmpty()) {
            item {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = "Refreshing nearby states"
                        },
                    color = NowColors.Blue600,
                    trackColor = NowColors.Blue100,
                )
            }
        }

        if (uiState.states.isEmpty() && uiState.refreshing) {
            items(3) {
                StateLoadingCard()
            }
        } else if (uiState.states.isEmpty()) {
            item {
                EmptyNearbyState(
                    canRetry = uiState.notice != BrowseNotice.AREA_REQUIRED,
                    onRetry = onRefresh,
                )
            }
        } else {
            items(
                items = uiState.states,
                key = { it.stateId },
            ) { state ->
                StateCard(
                    state = state,
                    nowMillis = nowMillis,
                    onClick = { onStateClick(state.stateId) },
                )
            }
        }
    }
}

@Composable
private fun HomeHeader(
    areaLabel: String,
    refreshing: Boolean,
    hasStates: Boolean,
    canRefresh: Boolean,
    onRefresh: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            modifier = Modifier.align(Alignment.CenterStart),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "NOW",
                style = NowType.TitleM,
                color = NowColors.Ink950,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = areaLabel,
                    style = NowType.BodyS,
                    color = NowColors.Ink500,
                )
                NetworkStatusPill(
                    text = when {
                        refreshing -> "REFRESHING"
                        hasStates -> "LIVE NETWORK"
                        else -> "READY"
                    },
                    active = refreshing || hasStates,
                )
            }
        }

        IconButton(
            onClick = onRefresh,
            enabled = canRefresh && !refreshing,
        ) {
            Icon(
                imageVector = Icons.Outlined.Refresh,
                contentDescription = "Refresh nearby states",
                tint = if (canRefresh) NowColors.Ink600 else NowColors.Ink400,
            )
        }
    }
}

@Composable
private fun NetworkStatusPill(
    text: String,
    active: Boolean,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = if (active) NowColors.LiveSoft else NowColors.Ink100,
        border = BorderStroke(
            1.dp,
            if (active) NowColors.LiveBorder else NowColors.BorderSubtle,
        ),
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            text = text,
            style = NowType.LabelS,
            color = if (active) NowColors.LiveText else NowColors.Ink500,
        )
    }
}

@Composable
private fun AskRefreshCard(
    refreshing: Boolean,
    canRefresh: Boolean,
    onRefresh: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = NowColors.Blue50,
        border = BorderStroke(1.dp, NowColors.Blue100),
    ) {
        Column(
            modifier = Modifier.padding(NowSpacing.Space4),
            verticalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(NowSpacing.Space1),
            ) {
                Text(
                    text = "ASK / REFRESH",
                    style = NowType.LabelM,
                    color = NowColors.Blue700,
                )
                Text(
                    text = "What do you need to know?",
                    style = NowType.TitleL,
                    color = NowColors.Ink950,
                )
                Text(
                    text = "Refresh nearby verified states and see what changed right now.",
                    style = NowType.BodyM,
                    color = NowColors.Ink600,
                )
            }

            Button(
                onClick = onRefresh,
                enabled = canRefresh && !refreshing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = NowColors.Primary,
                    contentColor = NowColors.White,
                    disabledContainerColor = NowColors.Ink200,
                    disabledContentColor = NowColors.Ink400,
                ),
            ) {
                Text(
                    text = if (refreshing) "Refreshing live states…" else "Refresh nearby",
                    style = NowType.LabelL,
                )
            }
        }
    }
}

@Composable
private fun LiveNetworkSummary(
    uiState: HomeUiState,
    nowMillis: Long,
) {
    val freshness = uiState.states.map { it.freshnessAt(nowMillis) }
    val live = freshness.count { it == FreshnessKind.LIVE }
    val aging = freshness.count { it == FreshnessKind.AGING }
    val attention = freshness.count {
        it == FreshnessKind.STALE || it == FreshnessKind.CONFLICT
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = NowColors.SurfacePrimary,
        border = BorderStroke(1.dp, NowColors.BorderSubtle),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NowSpacing.Space3, vertical = NowSpacing.Space3),
            horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
        ) {
            NetworkMetric(
                modifier = Modifier.weight(1f),
                value = live.toString(),
                label = "LIVE",
                valueColor = NowColors.LiveText,
            )
            NetworkMetric(
                modifier = Modifier.weight(1f),
                value = aging.toString(),
                label = "AGING",
                valueColor = NowColors.AgingText,
            )
            NetworkMetric(
                modifier = Modifier.weight(1f),
                value = attention.toString(),
                label = "NEEDS REFRESH",
                valueColor = if (attention > 0) NowColors.ConflictText else NowColors.Ink500,
            )
        }
    }
}

@Composable
private fun NetworkMetric(
    value: String,
    label: String,
    valueColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = value,
            style = NowType.DataMedium,
            color = valueColor,
        )
        Text(
            text = label,
            style = NowType.LabelS,
            color = NowColors.Ink500,
        )
    }
}

@Composable
private fun StateLoadingCard() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(148.dp)
            .clearAndSetSemantics {
                contentDescription = "Loading nearby state"
            },
        shape = MaterialTheme.shapes.large,
        color = NowColors.SurfacePrimary,
        border = BorderStroke(1.dp, NowColors.BorderSubtle),
    ) {
        Column(
            modifier = Modifier.padding(NowSpacing.Space4),
            verticalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
        ) {
            LoadingBar(widthFraction = 0.28f)
            LoadingBar(widthFraction = 0.62f)
            LoadingBar(widthFraction = 0.44f, height = 28.dp)
            LoadingBar(widthFraction = 0.36f)
        }
    }
}

@Composable
private fun LoadingBar(
    widthFraction: Float,
    height: androidx.compose.ui.unit.Dp = 12.dp,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(height),
        shape = MaterialTheme.shapes.small,
        color = NowColors.Ink100,
    ) {}
}

@Composable
private fun EmptyNearbyState(
    canRetry: Boolean,
    onRetry: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = NowColors.SurfacePrimary,
        border = BorderStroke(1.dp, NowColors.BorderSubtle),
    ) {
        Column(
            modifier = Modifier.padding(NowSpacing.Space4),
            verticalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
        ) {
            Text(
                text = "No live states nearby yet.",
                style = NowType.TitleS,
                color = NowColors.Ink950,
            )
            Text(
                text = "Try again when live state data is available for this area.",
                style = NowType.BodyM,
                color = NowColors.Ink500,
            )
            if (canRetry) {
                TextButton(onClick = onRetry) {
                    Text("Try again")
                }
            }
        }
    }
}
