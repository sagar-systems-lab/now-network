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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.sagarsystemslab.nownetwork.designsystem.NowColors
import com.sagarsystemslab.nownetwork.designsystem.NowSpacing
import com.sagarsystemslab.nownetwork.designsystem.NowType
import com.sagarsystemslab.nownetwork.model.StateSummary

@Composable
fun FreshnessChip(
    freshness: FreshnessKind,
    modifier: Modifier = Modifier,
) {
    val palette = freshnessPalette(freshness)

    Surface(
        modifier = modifier.semantics {
            contentDescription = "Freshness ${freshness.name}"
            stateDescription = freshness.name
        },
        shape = MaterialTheme.shapes.extraLarge,
        color = palette.background,
        border = BorderStroke(1.dp, palette.border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(7.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = palette.dot,
            ) {}
            Spacer(Modifier.width(NowSpacing.Space1))
            Text(
                text = freshness.name,
                style = NowType.LabelM,
                color = palette.text,
            )
        }
    }
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
                color = freshnessPalette(freshness).text,
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

    val informational = notice == BrowseNotice.AREA_REQUIRED

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (informational) NowColors.InfoSoft else NowColors.StaleSoft,
        border = BorderStroke(
            1.dp,
            if (informational) NowColors.InfoBorder else NowColors.StaleBorder,
        ),
    ) {
        Text(
            modifier = Modifier.padding(NowSpacing.Space3),
            text = text,
            style = NowType.BodyM,
            color = if (informational) NowColors.InfoText else NowColors.Ink600,
        )
    }
}

private data class FreshnessPalette(
    val text: Color,
    val background: Color,
    val border: Color,
    val dot: Color,
)

private fun freshnessPalette(kind: FreshnessKind): FreshnessPalette =
    when (kind) {
        FreshnessKind.LIVE -> FreshnessPalette(
            text = NowColors.LiveText,
            background = NowColors.LiveSoft,
            border = NowColors.LiveBorder,
            dot = NowColors.LiveDot,
        )
        FreshnessKind.AGING -> FreshnessPalette(
            text = NowColors.AgingText,
            background = NowColors.AgingSoft,
            border = NowColors.AgingBorder,
            dot = NowColors.AgingDot,
        )
        FreshnessKind.STALE -> FreshnessPalette(
            text = NowColors.StaleText,
            background = NowColors.StaleSoft,
            border = NowColors.StaleBorder,
            dot = NowColors.StaleDot,
        )
        FreshnessKind.CONFLICT -> FreshnessPalette(
            text = NowColors.ConflictText,
            background = NowColors.ConflictSoft,
            border = NowColors.ConflictBorder,
            dot = NowColors.ConflictDot,
        )
        FreshnessKind.UNKNOWN -> FreshnessPalette(
            text = NowColors.Ink500,
            background = NowColors.Ink100,
            border = NowColors.Ink300,
            dot = NowColors.Ink400,
        )
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
