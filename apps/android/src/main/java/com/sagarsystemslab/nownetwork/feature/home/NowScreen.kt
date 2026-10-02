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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.sagarsystemslab.nownetwork.designsystem.NowPrimaryButton
import com.sagarsystemslab.nownetwork.designsystem.NowSecondaryButton
import com.sagarsystemslab.nownetwork.designsystem.NowSpacing
import com.sagarsystemslab.nownetwork.designsystem.NowType
import com.sagarsystemslab.nownetwork.feature.state.BrowseNotice
import com.sagarsystemslab.nownetwork.feature.state.BrowseNoticeCard
import com.sagarsystemslab.nownetwork.feature.state.HomeUiState
import com.sagarsystemslab.nownetwork.feature.state.StateCard
import com.sagarsystemslab.nownetwork.feature.state.rememberVisibleServerTime
import com.sagarsystemslab.nownetwork.model.StateSummary

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
) {
    val nowMillis = rememberVisibleServerTime(serverNowMillis)
    val refreshCandidate = uiState.states.firstOrNull { it.needsRefresh() }

    LazyColumn(
        modifier = Modifier.testTag("screen-now"),
        contentPadding = PaddingValues(
            start = NowSpacing.PageHorizontal,
            top = NowSpacing.Space3,
            end = NowSpacing.PageHorizontal,
            bottom = NowSpacing.Space6,
        ),
        verticalArrangement = Arrangement.spacedBy(NowSpacing.Space4),
    ) {
        item {
            HomeHeader(
                areaLabel = uiState.areaLabel,
                darkTheme = darkTheme,
                refreshing = uiState.refreshing,
                refreshEnabled = uiState.notice != BrowseNotice.AREA_REQUIRED,
                onDarkThemeChange = onDarkThemeChange,
                onRefresh = onRefresh,
                onSettingsClick = onSettingsClick,
            )
        }

        item {
            AskRefreshCard(
                refreshCandidate = refreshCandidate,
                refreshing = uiState.refreshing,
                refreshEnabled = uiState.notice != BrowseNotice.AREA_REQUIRED,
                onOpenState = onStateClick,
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

        item {
            SectionHeading(
                title = "Nearby now",
                supporting = if (uiState.states.isEmpty()) {
                    "Fresh physical-state information for this area."
                } else {
                    "${uiState.states.size} current physical state${if (uiState.states.size == 1) "" else "s"} nearby."
                },
            )
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
            items(2) {
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

        item {
            EarnNearbyCard(
                onEarnClick = onEarnClick,
            )
        }
    }
}

@Composable
private fun HomeHeader(
    areaLabel: String,
    darkTheme: Boolean,
    refreshing: Boolean,
    refreshEnabled: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "NOW",
                style = NowType.TitleL,
                color = NowColors.Ink950,
            )
            Text(
                text = areaLabel,
                style = NowType.BodyS,
                color = NowColors.Ink500,
                maxLines = 1,
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(
                imageVector = if (darkTheme) {
                    Icons.Outlined.DarkMode
                } else {
                    Icons.Outlined.LightMode
                },
                contentDescription = null,
                tint = NowColors.Ink500,
                modifier = Modifier.size(18.dp),
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
        }

        IconButton(
            onClick = onRefresh,
            enabled = !refreshing && refreshEnabled,
        ) {
            Icon(
                imageVector = Icons.Outlined.Refresh,
                contentDescription = "Refresh nearby states",
                tint = NowColors.Ink600,
            )
        }

        IconButton(
            onClick = onSettingsClick,
        ) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = "Open settings",
                tint = NowColors.Ink600,
            )
        }
    }
}

@Composable
private fun AskRefreshCard(
    refreshCandidate: StateSummary?,
    refreshing: Boolean,
    refreshEnabled: Boolean,
    onOpenState: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("home-ask-refresh"),
        shape = MaterialTheme.shapes.large,
        color = NowColors.InfoSoft,
        border = BorderStroke(1.dp, NowColors.InfoBorder),
    ) {
        Column(
            modifier = Modifier.padding(NowSpacing.Space4),
            verticalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(42.dp),
                    shape = CircleShape,
                    color = NowColors.Blue50,
                    border = BorderStroke(1.dp, NowColors.Blue100),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = null,
                            tint = NowColors.Blue600,
                            modifier = Modifier.size(21.dp),
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "What do you need to know?",
                        style = NowType.TitleM,
                        color = NowColors.Ink950,
                    )
                    Text(
                        text = "Refresh a stale nearby state with fresh proof.",
                        style = NowType.BodyM,
                        color = NowColors.Ink600,
                    )
                }
            }

            if (refreshCandidate != null) {
                HorizontalDivider(color = NowColors.InfoBorder)
                Column(
                    verticalArrangement = Arrangement.spacedBy(NowSpacing.Space1),
                ) {
                    Text(
                        text = "Needs fresh proof",
                        style = NowType.LabelM,
                        color = NowColors.InfoText,
                    )
                    Text(
                        text = refreshCandidate.title,
                        style = NowType.TitleS,
                        color = NowColors.Ink950,
                    )
                    Text(
                        text = refreshCandidate.question,
                        style = NowType.BodyS,
                        color = NowColors.Ink600,
                        maxLines = 2,
                    )
                }

                NowPrimaryButton(
                    text = "Open stale state",
                    onClick = { onOpenState(refreshCandidate.stateId) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    text = "Nothing nearby is currently marked stale.",
                    style = NowType.BodyS,
                    color = NowColors.Ink600,
                )
                NowSecondaryButton(
                    text = if (refreshing) "Checking nearby…" else "Check nearby",
                    onClick = onRefresh,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !refreshing && refreshEnabled,
                )
            }
        }
    }
}

@Composable
private fun SectionHeading(
    title: String,
    supporting: String,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(NowSpacing.Space1),
    ) {
        Text(
            text = title,
            style = NowType.TitleS,
            color = NowColors.Ink950,
        )
        Text(
            text = supporting,
            style = NowType.BodyS,
            color = NowColors.Ink500,
        )
    }
}

@Composable
private fun EarnNearbyCard(
    onEarnClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("home-earn-entry"),
        shape = MaterialTheme.shapes.large,
        color = NowColors.SurfacePrimary,
        border = BorderStroke(1.dp, NowColors.BorderSubtle),
    ) {
        Row(
            modifier = Modifier.padding(NowSpacing.Space4),
            horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = MaterialTheme.shapes.medium,
                color = NowColors.LiveSoft,
                border = BorderStroke(1.dp, NowColors.LiveBorder),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.WorkOutline,
                        contentDescription = null,
                        tint = NowColors.LiveText,
                        modifier = Modifier.size(21.dp),
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "Earn nearby",
                        style = NowType.TitleS,
                        color = NowColors.Ink950,
                    )
                    Text(
                        text = "Refresh stale real-world states nearby and earn when proof is verified.",
                        style = NowType.BodyM,
                        color = NowColors.Ink600,
                    )
                }

                NowSecondaryButton(
                    text = "Browse opportunities",
                    onClick = onEarnClick,
                    modifier = Modifier.fillMaxWidth(),
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
            .height(144.dp)
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
            LoadingBar(widthFraction = 0.24f)
            LoadingBar(widthFraction = 0.58f)
            LoadingBar(widthFraction = 0.42f, height = 28.dp)
            LoadingBar(widthFraction = 0.34f)
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
            verticalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(NowSpacing.Space1),
            ) {
                Text(
                    text = "No nearby states yet",
                    style = NowType.TitleS,
                    color = NowColors.Ink950,
                )
                Text(
                    text = "Fresh physical-state information will appear here when this area has coverage.",
                    style = NowType.BodyM,
                    color = NowColors.Ink500,
                )
            }

            if (canRetry) {
                NowSecondaryButton(
                    text = "Check again",
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun StateSummary.needsRefresh(): Boolean =
    !conflictActive && freshnessStatus?.uppercase() == "STALE"
