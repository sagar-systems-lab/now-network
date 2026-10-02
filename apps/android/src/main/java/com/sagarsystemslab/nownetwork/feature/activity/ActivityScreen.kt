package com.sagarsystemslab.nownetwork.feature.activity

import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.sagarsystemslab.nownetwork.designsystem.NowColors
import com.sagarsystemslab.nownetwork.designsystem.NowMotion
import com.sagarsystemslab.nownetwork.designsystem.NowSpacing
import com.sagarsystemslab.nownetwork.designsystem.NowStatusChip
import com.sagarsystemslab.nownetwork.designsystem.NowStatusTone
import com.sagarsystemslab.nownetwork.designsystem.NowType
import com.sagarsystemslab.nownetwork.designsystem.nowMotionDuration
import com.sagarsystemslab.nownetwork.designsystem.rememberNowMotionEnabled
import com.sagarsystemslab.nownetwork.feature.state.humanizeStatus
import com.sagarsystemslab.nownetwork.feature.state.rememberVisibleServerTime
import com.sagarsystemslab.nownetwork.model.ActivityItem

@Composable
fun ActivityScreen(
    uiState: ActivityUiState,
    serverNowMillis: () -> Long,
    onPaymentClick: (String) -> Unit,
    onReceiptClick: (String) -> Unit,
) {
    val nowMillis = rememberVisibleServerTime(serverNowMillis)
    val listMotionDuration = nowMotionDuration(
        enabled = rememberNowMotionEnabled(),
        durationMillis = NowMotion.StateMillis,
    )

    LazyColumn(
        modifier = Modifier.testTag("screen-activity"),
        contentPadding = PaddingValues(
            start = NowSpacing.PageHorizontal,
            top = NowSpacing.Space3,
            end = NowSpacing.PageHorizontal,
            bottom = NowSpacing.Space8,
        ),
        verticalArrangement = Arrangement.spacedBy(NowSpacing.Space4),
    ) {
        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(NowSpacing.Space1),
            ) {
                Text(
                    text = "ACTIVITY",
                    style = NowType.TitleL,
                    color = NowColors.Ink950,
                )
                Text(
                    text = "Pending work stays above history so recovery is always easy to find.",
                    style = NowType.BodyM,
                    color = NowColors.Ink600,
                )
            }
        }

        if (uiState.active.isNotEmpty()) {
            item {
                SectionLabel(
                    title = "Needs attention",
                    supporting = "Open an active payment to resume authoritative reconciliation.",
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
                    modifier = Modifier.animateItem(
                        fadeInSpec = tween(listMotionDuration),
                        placementSpec = tween(listMotionDuration),
                        fadeOutSpec = tween(listMotionDuration),
                    ),
                    onClick = operationClick(
                        item = item,
                        onPaymentClick = onPaymentClick,
                        onReceiptClick = onReceiptClick,
                    ),
                )
            }
        }

        if (uiState.completed.isNotEmpty()) {
            item {
                SectionLabel(
                    title = "Completed",
                    supporting = "Durable history saved on this device.",
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
                    modifier = Modifier.animateItem(
                        fadeInSpec = tween(listMotionDuration),
                        placementSpec = tween(listMotionDuration),
                        fadeOutSpec = tween(listMotionDuration),
                    ),
                    onClick = operationClick(
                        item = item,
                        onPaymentClick = onPaymentClick,
                        onReceiptClick = onReceiptClick,
                    ),
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
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val status = activityStatus(item)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick == null) Modifier else Modifier.clickable(onClick = onClick),
            )
            .testTag("activity-row-" + item.operationId)
            .semantics {
                stateDescription = status
            },
        shape = MaterialTheme.shapes.large,
        color = NowColors.SurfacePrimary,
        border = BorderStroke(
            1.dp,
            if (active) NowColors.AgingBorder else NowColors.BorderSubtle,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(NowSpacing.Space4),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = MaterialTheme.shapes.medium,
                color = if (active) NowColors.AgingSoft else NowColors.LiveSoft,
                border = BorderStroke(
                    1.dp,
                    if (active) NowColors.AgingBorder else NowColors.LiveBorder,
                ),
            ) {
                androidx.compose.foundation.layout.Box(
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (item.type.uppercase() in setOf("SETTLEMENT", "PAYOUT")) {
                            Icons.Outlined.Payments
                        } else {
                            Icons.Outlined.History
                        },
                        contentDescription = null,
                        tint = if (active) NowColors.AgingText else NowColors.LiveText,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
                ) {
                    Text(
                        text = activityTitle(item),
                        style = NowType.TitleS,
                        color = NowColors.Ink950,
                        modifier = Modifier.weight(1f),
                    )
                    NowStatusChip(
                        label = status.uppercase(),
                        tone = if (active) NowStatusTone.AGING else NowStatusTone.LIVE,
                        accessibilityLabel = status,
                    )
                }

                Text(
                    text = if (active && onClick != null) {
                        "Open to continue or check recovery."
                    } else if (active) {
                        "This operation is still being tracked."
                    } else if (onClick != null) {
                        "Open durable result."
                    } else {
                        "Completed."
                    },
                    style = NowType.BodyS,
                    color = NowColors.Ink600,
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
            modifier = Modifier.padding(NowSpacing.Space5),
            verticalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
        ) {
            Text(
                text = "No activity yet",
                style = NowType.TitleM,
                color = NowColors.Ink950,
            )
            Text(
                text = "Refresh funding, claims, evidence, settlement, and receipts will appear here when they exist.",
                style = NowType.BodyM,
                color = NowColors.Ink500,
            )
        }
    }
}

private fun operationClick(
    item: ActivityItem,
    onPaymentClick: (String) -> Unit,
    onReceiptClick: (String) -> Unit,
): (() -> Unit)? =
    if (item.type.uppercase() in setOf("SETTLEMENT", "PAYOUT")) {
        if (item.localState.uppercase() == "PAID") {
            { onReceiptClick(item.entityId) }
        } else {
            { onPaymentClick(item.entityId) }
        }
    } else {
        null
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
        elapsedSeconds < 60L -> elapsedSeconds.toString() + "s ago"
        elapsedSeconds < 3_600L -> (elapsedSeconds / 60L).toString() + "m ago"
        elapsedSeconds < 86_400L -> (elapsedSeconds / 3_600L).toString() + "h ago"
        else -> (elapsedSeconds / 86_400L).toString() + "d ago"
    }
}
