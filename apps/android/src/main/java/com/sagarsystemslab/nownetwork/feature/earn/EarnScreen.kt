package com.sagarsystemslab.nownetwork.feature.earn

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.sagarsystemslab.nownetwork.designsystem.NowColors
import com.sagarsystemslab.nownetwork.designsystem.NowSpacing
import com.sagarsystemslab.nownetwork.designsystem.NowType
import com.sagarsystemslab.nownetwork.feature.state.rememberVisibleServerTime
import com.sagarsystemslab.nownetwork.model.OpportunitySummary

@Composable
fun EarnScreen(
    uiState: EarnUiState,
    rewardText: (OpportunitySummary) -> String,
    serverNowMillis: () -> Long,
    onRefresh: () -> Unit,
    onOpportunityClick: (String) -> Unit,
) {
    val nowMillis = rememberVisibleServerTime(serverNowMillis)
    val visibleOpportunities = uiState.opportunities.filter {
        it.expiresAtMillis > nowMillis
    }

    LazyColumn(
        modifier = Modifier.testTag("screen-earn"),
        contentPadding = PaddingValues(
            start = NowSpacing.PageHorizontal,
            top = NowSpacing.Space4,
            end = NowSpacing.PageHorizontal,
            bottom = NowSpacing.Space6,
        ),
        verticalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(NowSpacing.Space1),
                ) {
                    Text(
                        text = "Earn nearby",
                        style = NowType.TitleXL,
                        color = NowColors.Ink950,
                    )
                    Text(
                        text = uiState.areaLabel,
                        style = NowType.BodyS,
                        color = NowColors.Ink500,
                    )
                    Text(
                        text = "Refresh stale states around you.",
                        style = NowType.BodyM,
                        color = NowColors.Ink600,
                    )
                }

                IconButton(
                    onClick = onRefresh,
                    enabled = !uiState.refreshing &&
                        uiState.notice != EarnNotice.AREA_REQUIRED &&
                        uiState.notice != EarnNotice.AUTH_REQUIRED,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = "Refresh earning opportunities",
                        tint = NowColors.Ink600,
                    )
                }
            }
        }

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

        if (visibleOpportunities.isEmpty() && uiState.refreshing) {
            items(3) {
                OpportunityLoadingCard()
            }
        } else if (visibleOpportunities.isEmpty()) {
            item {
                EmptyEarnState(
                    canRetry = uiState.notice == EarnNotice.NONE ||
                        uiState.notice == EarnNotice.NETWORK_UNAVAILABLE ||
                        uiState.notice == EarnNotice.SERVER_UNAVAILABLE ||
                        uiState.notice == EarnNotice.DATA_UNAVAILABLE,
                    onRetry = onRefresh,
                )
            }
        } else {
            items(
                items = visibleOpportunities,
                key = { it.refreshId },
            ) { opportunity ->
                OpportunityCard(
                    opportunity = opportunity,
                    reward = rewardText(opportunity),
                    nowMillis = nowMillis,
                    onClaim = { onOpportunityClick(opportunity.refreshId) },
                )
            }
        }
    }
}

@Composable
private fun OpportunityCard(
    opportunity: OpportunitySummary,
    reward: String,
    nowMillis: Long,
    onClaim: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("opportunity-card-${opportunity.refreshId}"),
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
                Text(
                    text = reward,
                    style = NowType.DataMedium,
                    color = NowColors.Ink950,
                )
                Spacer(Modifier.weight(1f))
                AvailabilityChip(opportunity.claimable)
            }

            Text(
                text = opportunity.title,
                style = NowType.TitleS,
                color = NowColors.Ink800,
            )

            Text(
                text = opportunity.question,
                style = NowType.BodyM,
                color = NowColors.Ink600,
            )

            opportunity.locationName?.let { location ->
                Text(
                    text = location,
                    style = NowType.BodyS,
                    color = NowColors.Ink500,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
            ) {
                Text(
                    text = formatOpportunityDistance(opportunity.distanceMeters),
                    style = NowType.LabelM,
                    color = NowColors.Ink600,
                )
                val timeText = formatOpportunityTime(opportunity.expiresAtMillis, nowMillis)
                Text(
                    modifier = Modifier.semantics {
                        contentDescription = "Opportunity time: $timeText"
                    },
                    text = timeText,
                    style = NowType.LabelM,
                    color = if (opportunity.expiresAtMillis <= nowMillis) {
                        NowColors.ConflictText
                    } else {
                        NowColors.AgingText
                    },
                )
            }

            Text(
                text = proofSummary(opportunity),
                style = NowType.BodyS,
                color = NowColors.Ink500,
            )

            if (opportunity.cachedOnly) {
                Text(
                    text = "Saved opportunity · reconnect to confirm live availability.",
                    style = NowType.BodyS,
                    color = NowColors.StaleText,
                )
            }

            if (opportunity.claimable && !opportunity.cachedOnly) {
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onClaim,
                ) {
                    Text("Claim")
                }
            }
        }
    }
}

@Composable
private fun AvailabilityChip(claimable: Boolean) {
    val text = if (claimable) "AVAILABLE" else "FILLED"
    val background = if (claimable) NowColors.LiveSoft else NowColors.StaleSoft
    val border = if (claimable) NowColors.LiveBorder else NowColors.StaleBorder
    val foreground = if (claimable) NowColors.LiveText else NowColors.StaleText

    Surface(
        modifier = Modifier.semantics {
            stateDescription = text
            contentDescription = "Availability $text"
        },
        shape = MaterialTheme.shapes.extraLarge,
        color = background,
        border = BorderStroke(1.dp, border),
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            text = text,
            style = NowType.LabelM,
            color = foreground,
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

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (
            notice == EarnNotice.AREA_REQUIRED ||
            notice == EarnNotice.AUTH_REQUIRED
        ) {
            NowColors.InfoSoft
        } else {
            NowColors.StaleSoft
        },
        border = BorderStroke(
            1.dp,
            if (
                notice == EarnNotice.AREA_REQUIRED ||
                notice == EarnNotice.AUTH_REQUIRED
            ) {
                NowColors.InfoBorder
            } else {
                NowColors.StaleBorder
            },
        ),
    ) {
        Text(
            modifier = Modifier.padding(NowSpacing.Space3),
            text = message,
            style = NowType.BodyM,
            color = NowColors.Ink600,
        )
    }
}

@Composable
private fun OpportunityLoadingCard() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(172.dp)
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
            repeat(4) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(if (it == 0) 0.35f else 0.72f)
                        .height(if (it == 0) 24.dp else 12.dp),
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
            verticalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
        ) {
            Text(
                text = "Nothing nearby needs a refresh right now.",
                style = NowType.TitleS,
                color = NowColors.Ink950,
            )
            Text(
                text = "New opportunities will appear when nearby states need fresh proof.",
                style = NowType.BodyM,
                color = NowColors.Ink500,
            )
            if (canRetry) {
                TextButton(onClick = onRetry) {
                    Text("Check again")
                }
            }
        }
    }
}

private fun proofSummary(opportunity: OpportunitySummary): String {
    val parts = buildList {
        opportunity.verificationClass?.let { add(it) }
        if (opportunity.mediaRequired == true) add("photo proof")
        if (opportunity.locationRequired == true) add("location check")
        opportunity.remainingSlots?.let { slots ->
            add(if (slots == 1) "1 slot" else "${slots} slots")
        }
    }

    return if (parts.isEmpty()) {
        "Proof requirements available online"
    } else {
        parts.joinToString(" · ")
    }
}
