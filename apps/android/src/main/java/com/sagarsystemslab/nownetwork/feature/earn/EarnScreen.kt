package com.sagarsystemslab.nownetwork.feature.earn

import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.sagarsystemslab.nownetwork.feature.common.*
import com.sagarsystemslab.nownetwork.model.GeoCenter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sagarsystemslab.nownetwork.designsystem.NowColors
import com.sagarsystemslab.nownetwork.designsystem.NowMotion
import com.sagarsystemslab.nownetwork.designsystem.NowNotice
import com.sagarsystemslab.nownetwork.designsystem.NowNoticeTone
import com.sagarsystemslab.nownetwork.designsystem.NowPrimaryButton
import com.sagarsystemslab.nownetwork.designsystem.NowSecondaryButton
import com.sagarsystemslab.nownetwork.designsystem.NowSpacing
import com.sagarsystemslab.nownetwork.designsystem.NowStatusChip
import com.sagarsystemslab.nownetwork.designsystem.NowStatusTone
import com.sagarsystemslab.nownetwork.designsystem.NowType
import com.sagarsystemslab.nownetwork.designsystem.nowMotionDuration
import com.sagarsystemslab.nownetwork.designsystem.rememberNowMotionEnabled
import com.sagarsystemslab.nownetwork.designsystem.nowPulseOnChange
import com.sagarsystemslab.nownetwork.feature.state.rememberVisibleServerTime
import com.sagarsystemslab.nownetwork.model.OpportunitySummary

@Composable
fun EarnScreen(
    uiState: EarnUiState,
    rewardText: (OpportunitySummary) -> String,
    serverNowMillis: () -> Long,
    onRefresh: () -> Unit,
    onOpportunityClick: (String) -> Unit,
    onBrowseAreas: () -> Unit = onRefresh,
    onNotifications: () -> Unit = onRefresh,
    onProfile: () -> Unit = onRefresh,
    onHelp: () -> Unit = onRefresh,
    center: GeoCenter? = null,
    unread: Int = 0,
    onSearchArea: ((GeoCenter) -> Unit)? = null,
) {
    val nowMillis = rememberVisibleServerTime(serverNowMillis)
    val listMotionDuration = nowMotionDuration(
        enabled = rememberNowMotionEnabled(),
        durationMillis = NowMotion.StateMillis,
    )
    val visibleOpportunities = uiState.opportunities.filter {
        it.expiresAtMillis > nowMillis
    }
    val liveCount = visibleOpportunities.count {
        it.claimable && !it.cachedOnly
    }

    LazyColumn(
        modifier = Modifier.testTag("screen-earn"),
        contentPadding = PaddingValues(
            start = NowSpacing.PageHorizontal,
            top = NowSpacing.Space3,
            end = NowSpacing.PageHorizontal,
            bottom = NowSpacing.Space8,
        ),
        verticalArrangement = Arrangement.spacedBy(NowSpacing.Space4),
    ) {
        item {
            ExperienceHeader("EARN", "Nearby refresh opportunities", uiState.areaLabel, onBrowseAreas, onNotifications, onProfile, unread)
        }
        item { LiveMapCard(center, visibleOpportunities.mapNotNull { o -> o.center?.let { LiveMapPin(o.refreshId, o.title, it, if (o.claimable && !o.cachedOnly) "CLAIMABLE" else "UNKNOWN") } }, onOpportunityClick, onSearchArea = onSearchArea) }
        item { SyncStrip(uiState.refreshing, visibleOpportunities.size, uiState.notice != EarnNotice.NONE, onRefresh) }
        if (visibleOpportunities.isNotEmpty()) item { MetricStrip(listOf(liveCount.toString() to "Available here", visibleOpportunities.size.toString() to "Results shown")) }

        if (uiState.notice != EarnNotice.NONE) {
            item {
                EarnNoticeCard(
                    notice = uiState.notice,
                    hasCachedContent = visibleOpportunities.isNotEmpty(),
                )
            }
        }

        if (uiState.refreshing && visibleOpportunities.isNotEmpty()) {
            item {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = "Refreshing earning opportunities"
                        },
                    color = NowColors.Blue600,
                    trackColor = NowColors.Blue100,
                )
            }
        }

        if (visibleOpportunities.isNotEmpty()) {
            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(NowSpacing.Space1),
                ) {
                    Text(
                        text = "Nearby opportunities",
                        style = NowType.TitleS,
                        color = NowColors.Ink950,
                        modifier = Modifier.semantics {
                            heading()
                        },
                    )
                    Text(
                        text = if (liveCount == 1) {
                            "1 task is available to claim right now."
                        } else {
                            liveCount.toString() + " tasks are available to claim right now."
                        },
                        style = NowType.BodyS,
                        color = NowColors.Ink500,
                    )
                }
            }
        }

        if (visibleOpportunities.isEmpty() && uiState.refreshing) {
            items(3) {
                OpportunityLoadingCard()
            }
        } else if (visibleOpportunities.isEmpty() && uiState.notice == EarnNotice.NONE) {
            item {
                EmptyProofCard(
                    "Nothing nearby needs fresh proof",
                    "New earning opportunities appear when nearby states become stale or need another verified observation.",
                    onBrowseAreas, onHelp,
                )
            }
        } else if (visibleOpportunities.isNotEmpty()) {
            items(
                items = visibleOpportunities,
                key = { it.refreshId },
            ) { opportunity ->
                OpportunityCard(
                    opportunity = opportunity,
                    reward = rewardText(opportunity),
                    nowMillis = nowMillis,
                    onOpen = { onOpportunityClick(opportunity.refreshId) },
                    modifier = Modifier.animateItem(
                        fadeInSpec = tween(listMotionDuration),
                        placementSpec = tween(listMotionDuration),
                        fadeOutSpec = tween(listMotionDuration),
                    ),
                )
            }
        }
    }
}

@Composable
private fun EarnHeader(
    areaLabel: String,
    availableCount: Int,
    refreshing: Boolean,
    refreshEnabled: Boolean,
    onRefresh: () -> Unit,
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
                text = "EARN",
                style = NowType.TitleL,
                color = NowColors.Ink950,
                modifier = Modifier.semantics {
                    heading()
                },
            )
            Text(
                text = areaLabel,
                style = NowType.BodyS,
                color = NowColors.Ink500,
            )
            Text(
                text = if (availableCount > 0) {
                    "Prove what is true nearby and earn for verified evidence."
                } else {
                    "Nearby refresh requests appear here when fresh proof is needed."
                },
                style = NowType.BodyM,
                color = NowColors.Ink600,
            )
        }

        IconButton(
            onClick = onRefresh,
            enabled = !refreshing && refreshEnabled,
        ) {
            Icon(
                imageVector = Icons.Outlined.Refresh,
                contentDescription = "Refresh earning opportunities",
                tint = NowColors.Ink600,
            )
        }
    }
}

@Composable
private fun OpportunityCard(
    opportunity: OpportunitySummary,
    reward: String,
    nowMillis: Long,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val timeText = formatOpportunityTime(opportunity.expiresAtMillis, nowMillis)
    com.sagarsystemslab.nownetwork.designsystem.NowGlassCard(modifier = modifier.testTag("opportunity-card-" + opportunity.refreshId), emphasized = opportunity.claimable) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            com.sagarsystemslab.nownetwork.feature.common.CategoryArtwork(opportunity.title, Modifier.size(60.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(opportunity.title, style = NowType.TitleS, color = NowColors.Ink950)
                Text(locationSummary(opportunity), style = NowType.BodyS, color = NowColors.Ink600)
                Text(timeText, style = NowType.BodyS, color = NowColors.AgingText)
            }
            Text(reward, style = NowType.LabelL, color = NowColors.Blue600)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OpportunityAvailability(opportunity)
            Text(buildList {
                if(opportunity.mediaRequired == true) add("Fresh photo")
                if(opportunity.locationRequired == true) add("On site")
                opportunity.remainingSlots?.let { add("$it slots") }
            }.joinToString(" · "), style = NowType.BodyS, color = NowColors.Ink600, modifier = Modifier.weight(1f))
        }
        when {
            opportunity.cachedOnly -> NowNotice("Saved opportunity · reconnect to confirm availability.")
            opportunity.claimable -> NowPrimaryButton("View opportunity", onOpen, Modifier.fillMaxWidth())
            else -> NowSecondaryButton("Opportunity filled", {}, Modifier.fillMaxWidth(), enabled = false)
        }
    }
}

@Composable
private fun OpportunityAvailability(
    opportunity: OpportunitySummary,
) {
    when {
        opportunity.cachedOnly -> {
            NowStatusChip(
                label = "SAVED",
                tone = NowStatusTone.STALE,
                accessibilityLabel = "Saved availability requires reconnect",
            )
        }

        opportunity.claimable -> {
            NowStatusChip(
                label = "AVAILABLE",
                tone = NowStatusTone.LIVE,
                accessibilityLabel = "Opportunity available",
            )
        }

        else -> {
            NowStatusChip(
                label = "FILLED",
                tone = NowStatusTone.STALE,
                accessibilityLabel = "Opportunity filled",
            )
        }
    }
}

@Composable
private fun OpportunityMetaRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    emphasized: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (emphasized) NowColors.AgingText else NowColors.Ink500,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = NowType.BodyS,
            color = if (emphasized) NowColors.AgingText else NowColors.Ink600,
        )
    }
}

@Composable
private fun ProofRequirement(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
    ) {
        Surface(
            modifier = Modifier.size(28.dp),
            shape = MaterialTheme.shapes.small,
            color = NowColors.Blue50,
            border = BorderStroke(1.dp, NowColors.Blue100),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = NowColors.Blue600,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Text(
            text = text,
            style = NowType.BodyS,
            color = NowColors.Ink700,
        )
    }
}

@Composable
private fun EarnNoticeCard(
    notice: EarnNotice,
    hasCachedContent: Boolean,
) {
    val message = when (notice) {
        EarnNotice.AREA_REQUIRED ->
            "Choose a browse area to see nearby earning opportunities."
        EarnNotice.AUTH_REQUIRED ->
            "Earning opportunities are unavailable until a session is ready."
        EarnNotice.NETWORK_UNAVAILABLE ->
            if (hasCachedContent) {
                "Live connection unavailable · saved opportunities are shown."
            } else {
                "Earning opportunities are unavailable right now."
            }
        EarnNotice.SERVER_UNAVAILABLE ->
            if (hasCachedContent) {
                "Live opportunity service is temporarily unavailable · saved items are shown."
            } else {
                "Opportunity service is temporarily unavailable."
            }
        EarnNotice.DATA_UNAVAILABLE ->
            if (hasCachedContent) {
                "Some opportunity details could not be refreshed."
            } else {
                "Opportunity data could not be loaded."
            }
        EarnNotice.NONE -> return
    }

    NowNotice(
        body = message,
        tone = if (
            notice == EarnNotice.AREA_REQUIRED ||
            notice == EarnNotice.AUTH_REQUIRED
        ) {
            NowNoticeTone.INFO
        } else {
            NowNoticeTone.NEUTRAL
        },
    )
}

@Composable
private fun OpportunityLoadingCard() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clearAndSetSemantics {
                contentDescription = "Loading earning opportunity"
            },
        shape = MaterialTheme.shapes.large,
        color = NowColors.SurfacePrimary,
        border = BorderStroke(1.dp, NowColors.BorderSubtle),
    ) {
        Column(
            modifier = Modifier.padding(NowSpacing.Space4),
            verticalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
        ) {
            repeat(5) { index ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(
                            when (index) {
                                0 -> 0.30f
                                1 -> 0.58f
                                else -> 0.76f
                            },
                        )
                        .height(if (index == 1) 28.dp else 12.dp),
                    shape = MaterialTheme.shapes.small,
                    color = NowColors.Ink100,
                ) {}
            }
        }
    }
}

@Composable
private fun EmptyEarnState(
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
                    text = "Nothing nearby needs fresh proof",
                    style = NowType.TitleS,
                    color = NowColors.Ink950,
                )
                Text(
                    text = "New earning opportunities appear when nearby states become stale or need another verified observation.",
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

@Composable
private fun locationSummary(opportunity: OpportunitySummary): String {
    val place = opportunity.locationName
        ?.takeIf(String::isNotBlank)
        ?: opportunity.displayAddress
            ?.takeIf(String::isNotBlank)
        ?: "Location available in task"

    return place + " · " + com.sagarsystemslab.nownetwork.experience.displayDistance(opportunity.distanceMeters)
}

private fun humanizeRequirement(value: String): String =
    value
        .lowercase()
        .split('_')
        .joinToString(" ") { token ->
            token.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase() else it.toString()
            }
        }
        .replaceFirstChar {
            if (it.isLowerCase()) it.titlecase() else it.toString()
        }
