package com.sagarsystemslab.nownetwork.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.sagarsystemslab.nownetwork.designsystem.NowColors
import com.sagarsystemslab.nownetwork.designsystem.NowSpacing
import com.sagarsystemslab.nownetwork.designsystem.NowType
import com.sagarsystemslab.nownetwork.feature.state.BrowseNotice
import com.sagarsystemslab.nownetwork.feature.state.BrowseNoticeCard
import com.sagarsystemslab.nownetwork.feature.state.HomeUiState
import com.sagarsystemslab.nownetwork.feature.state.StateCard
import com.sagarsystemslab.nownetwork.feature.state.rememberVisibleServerTime

@Composable
fun NowScreen(
    uiState: HomeUiState,
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    serverNowMillis: () -> Long,
    onRefresh: () -> Unit,
    onStateClick: (String) -> Unit,
) {
    val nowMillis = rememberVisibleServerTime(serverNowMillis)

    LazyColumn(
        modifier = Modifier
            .testTag("screen-now"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = NowSpacing.PageHorizontal,
            top = NowSpacing.Space4,
            end = NowSpacing.PageHorizontal,
            bottom = NowSpacing.Space6,
        ),
        verticalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(NowSpacing.Space1),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = "NOW",
                            style = NowType.TitleM,
                            color = NowColors.Ink950,
                        )
                        Text(
                            text = uiState.areaLabel,
                            style = NowType.BodyS,
                            color = NowColors.Ink500,
                        )
                    }

                    Text(
                        text = if (darkTheme) "Dark" else "Light",
                        style = NowType.LabelM,
                        color = NowColors.Ink500,
                    )
                    Switch(
                        checked = darkTheme,
                        onCheckedChange = onDarkThemeChange,
                        modifier = Modifier.semantics {
                            contentDescription = "Theme"
                            stateDescription = if (darkTheme) {
                                "Dark theme"
                            } else {
                                "Light theme"
                            }
                        },
                    )

                    IconButton(
                        onClick = onRefresh,
                        enabled = !uiState.refreshing &&
                            uiState.notice != BrowseNotice.AREA_REQUIRED,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "Refresh nearby states",
                            tint = NowColors.Ink600,
                        )
                    }
                }

                Text(
                    text = "What’s true around you right now?",
                    style = NowType.TitleXL,
                    color = NowColors.Ink950,
                )
            }
        }

        if (uiState.notice != BrowseNotice.NONE) {
            item {
                BrowseNoticeCard(
                    notice = uiState.notice,
                    hasCachedContent = uiState.states.isNotEmpty(),
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
                    text = "Current physical states, ordered by freshness and distance.",
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
        border = androidx.compose.foundation.BorderStroke(1.dp, NowColors.BorderSubtle),
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
        border = androidx.compose.foundation.BorderStroke(1.dp, NowColors.BorderSubtle),
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
