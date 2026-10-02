package com.sagarsystemslab.nownetwork.feature.state

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sagarsystemslab.nownetwork.designsystem.NowColors
import com.sagarsystemslab.nownetwork.designsystem.NowNotice
import com.sagarsystemslab.nownetwork.designsystem.NowNoticeTone
import com.sagarsystemslab.nownetwork.designsystem.NowStatusChip
import com.sagarsystemslab.nownetwork.designsystem.NowStatusTone
import com.sagarsystemslab.nownetwork.designsystem.NowSpacing
import com.sagarsystemslab.nownetwork.designsystem.NowType
import com.sagarsystemslab.nownetwork.model.StateSummary

@Composable
fun FreshnessChip(
    freshness: FreshnessKind,
    modifier: Modifier = Modifier,
) {
    NowStatusChip(
        label = freshness.name,
        tone = freshness.toStatusTone(),
        modifier = modifier,
        accessibilityLabel = "Freshness ${freshness.name}",
    )
}

@Composable
fun StateCard(
    state: StateSummary,
    nowMillis: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val freshness = state.freshnessAt(nowMillis)

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .testTag("state-card-${state.stateId}"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = NowColors.SurfacePrimary),
        border = BorderStroke(1.dp, NowColors.BorderSubtle),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(NowSpacing.Space4),
            verticalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FreshnessChip(freshness)
                Spacer(Modifier.weight(1f))
                state.distanceMeters?.let { distance ->
                    Text(
                        text = formatDistance(distance),
                        style = NowType.LabelM,
                        color = NowColors.Ink500,
                    )
                }
            }

            Text(
                text = state.title,
                style = NowType.TitleS,
                color = NowColors.Ink800,
            )

            Text(
                text = formatStateValue(state.valueJson, state.unitCode),
                style = NowType.DataLarge,
                color = NowColors.Ink950,
            )

            Text(
                text = relativeObservedTime(state.observedAtMillis, nowMillis),
                style = NowType.LabelM,
                color = freshnessTextColor(freshness),
            )

            if (!state.refreshStatus.isNullOrBlank()) {
                Text(
                    text = humanizeStatus(state.refreshStatus),
                    style = NowType.BodyS,
                    color = NowColors.InfoText,
                )
            }
        }
    }
}

@Composable
fun BrowseNoticeCard(
    notice: BrowseNotice,
    hasCachedContent: Boolean,
    modifier: Modifier = Modifier,
) {
    if (notice == BrowseNotice.NONE) return

    val text = when (notice) {
        BrowseNotice.AREA_REQUIRED ->
            "Choose a browse area to see nearby live states."
        BrowseNotice.NETWORK_UNAVAILABLE ->
            if (hasCachedContent) {
                "Live connection unavailable · showing the latest verified states saved on this device."
            } else {
                "Nearby states are unavailable right now. Try again when connected."
            }
        BrowseNotice.SERVER_UNAVAILABLE ->
            if (hasCachedContent) {
                "Live updates are temporarily unavailable. Saved states are still shown."
            } else {
                "Live state service is temporarily unavailable."
            }
        BrowseNotice.DATA_UNAVAILABLE ->
            if (hasCachedContent) {
                "Some live details could not be refreshed. Saved states are still shown."
            } else {
                "State data could not be loaded."
            }
        BrowseNotice.NONE -> return
    }

    NowNotice(
        body = text,
        modifier = modifier,
        tone = if (notice == BrowseNotice.AREA_REQUIRED) {
            NowNoticeTone.INFO
        } else {
            NowNoticeTone.NEUTRAL
        },
    )
}

@Composable
private fun freshnessTextColor(kind: FreshnessKind) =
    when (kind) {
        FreshnessKind.LIVE -> NowColors.LiveText
        FreshnessKind.AGING -> NowColors.AgingText
        FreshnessKind.STALE -> NowColors.StaleText
        FreshnessKind.CONFLICT -> NowColors.ConflictText
        FreshnessKind.UNKNOWN -> NowColors.Ink500
    }

private fun FreshnessKind.toStatusTone(): NowStatusTone =
    when (this) {
        FreshnessKind.LIVE -> NowStatusTone.LIVE
        FreshnessKind.AGING -> NowStatusTone.AGING
        FreshnessKind.STALE -> NowStatusTone.STALE
        FreshnessKind.CONFLICT -> NowStatusTone.CONFLICT
        FreshnessKind.UNKNOWN -> NowStatusTone.STALE
    }

private fun formatDistance(distanceMeters: Double): String =
    if (distanceMeters < 1_000.0) {
        "${distanceMeters.toInt()} m"
    } else {
        String.format("%.1f km", distanceMeters / 1_000.0)
    }

fun humanizeStatus(status: String): String =
    status
        .lowercase()
        .split('_')
        .joinToString(" ") { token ->
            token.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
