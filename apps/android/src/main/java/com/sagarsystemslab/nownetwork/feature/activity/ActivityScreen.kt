package com.sagarsystemslab.nownetwork.feature.activity

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sagarsystemslab.nownetwork.designsystem.NowColors
import com.sagarsystemslab.nownetwork.designsystem.NowSpacing
import com.sagarsystemslab.nownetwork.designsystem.NowType
import com.sagarsystemslab.nownetwork.feature.state.humanizeStatus
import com.sagarsystemslab.nownetwork.feature.state.rememberVisibleServerTime
import com.sagarsystemslab.nownetwork.model.ActivityItem

@Composable
fun ActivityScreen(
    uiState: ActivityUiState,
    serverNowMillis: () -> Long,
) {
    val nowMillis = rememberVisibleServerTime(serverNowMillis)

    LazyColumn(
        modifier = Modifier.testTag("screen-activity"),
        contentPadding = PaddingValues(
            start = NowSpacing.PageHorizontal,
            top = NowSpacing.Space4,
            end = NowSpacing.PageHorizontal,
            bottom = NowSpacing.Space6,
        ),
        verticalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
    ) {
        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(NowSpacing.Space1),
            ) {
                Text(
                    text = "Your activity",
                    style = NowType.TitleXL,
                    color = NowColors.Ink950,
                )
                Text(
                    text = "Refreshes, earnings, and operations that still need attention.",
                    style = NowType.BodyM,
                    color = NowColors.Ink600,
                )
            }
        }

        if (uiState.active.isNotEmpty()) {
            item {
                SectionLabel(
                    title = "Active",
                    supporting = "Pending operations stay at the top.",
                )
            }

            items(
                items = uiState.active,
                key = { it.operationId },
            ) { item ->
                ActivityRow(
                    item = item,
                    nowMillis = nowMillis,
                    active = true,
                )
            }
        }

        if (uiState.completed.isNotEmpty()) {
            item {
                SectionLabel(
                    title = "Completed",
                    supporting = "Recent completed activity on this device.",
                )
            }

            items(
                items = uiState.completed,
                key = { it.operationId },
            ) { item ->
                ActivityRow(
                    item = item,
                    nowMillis = nowMillis,
                    active = false,
                )
            }
        }

        if (uiState.active.isEmpty() && uiState.completed.isEmpty()) {
            item {
                EmptyActivityState()
            }
        }
    }
}

@Composable
private fun SectionLabel(
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
private fun ActivityRow(
    item: ActivityItem,
    nowMillis: Long,
    active: Boolean,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("activity-row-${item.operationId}"),
        shape = MaterialTheme.shapes.large,
        color = NowColors.SurfacePrimary,
        border = BorderStroke(1.dp, NowColors.BorderSubtle),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(NowSpacing.Space4),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                modifier = Modifier.size(9.dp),
                shape = CircleShape,
                color = if (active) NowColors.AgingDot else NowColors.LiveDot,
            ) {}

            Spacer(Modifier.width(NowSpacing.Space3))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NowSpacing.Space1),
            ) {
                Text(
                    text = activityTitle(item),
                    style = NowType.TitleS,
                    color = NowColors.Ink950,
                )

                Text(
                    text = activityStatus(item),
                    style = NowType.BodyM,
                    color = if (active) NowColors.AgingText else NowColors.Ink600,
                )

                Text(
                    text = activityRelativeTime(item.updatedAtMillis, nowMillis),
                    style = NowType.BodyS,
                    color = NowColors.Ink500,
                )
            }
        }
    }
}

@Composable
private fun EmptyActivityState() {
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
                text = "No activity yet",
                style = NowType.TitleS,
                color = NowColors.Ink950,
            )
            Text(
                text = "Your refreshes and earnings will appear here.",
                style = NowType.BodyM,
                color = NowColors.Ink500,
            )
        }
    }
}

private fun activityTitle(item: ActivityItem): String =
    when (item.type.uppercase()) {
        "FUNDING", "REFRESH_FUNDING" -> "Refresh funded"
        "CLAIM", "OPPORTUNITY_CLAIM" -> "Task claimed"
        "EVIDENCE", "EVIDENCE_COMMIT", "EVIDENCE_UPLOAD" -> "Evidence submitted"
        "SETTLEMENT", "PAYOUT" -> "Payment"
        "REFUND" -> "Refund"
        else -> humanizeStatus(item.type)
    }

private fun activityStatus(item: ActivityItem): String =
    humanizeStatus(item.remoteState ?: item.localState)

private fun activityRelativeTime(
    updatedAtMillis: Long,
    nowMillis: Long,
): String {
    val elapsedSeconds = ((nowMillis - updatedAtMillis).coerceAtLeast(0L)) / 1_000L
    return when {
        elapsedSeconds < 5L -> "just now"
        elapsedSeconds < 60L -> "${elapsedSeconds}s ago"
        elapsedSeconds < 3_600L -> "${elapsedSeconds / 60L}m ago"
        elapsedSeconds < 86_400L -> "${elapsedSeconds / 3_600L}h ago"
        else -> "${elapsedSeconds / 86_400L}d ago"
    }
}
