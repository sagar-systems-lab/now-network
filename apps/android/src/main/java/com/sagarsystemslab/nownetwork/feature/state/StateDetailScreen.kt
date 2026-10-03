package com.sagarsystemslab.nownetwork.feature.state

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sagarsystemslab.nownetwork.designsystem.NowColors
import com.sagarsystemslab.nownetwork.designsystem.NowNotice
import com.sagarsystemslab.nownetwork.designsystem.NowNoticeTone
import com.sagarsystemslab.nownetwork.designsystem.NowPrimaryButton
import com.sagarsystemslab.nownetwork.designsystem.NowSecondaryButton
import com.sagarsystemslab.nownetwork.designsystem.NowSpacing
import com.sagarsystemslab.nownetwork.designsystem.NowType
import com.sagarsystemslab.nownetwork.designsystem.nowLivePulse
import com.sagarsystemslab.nownetwork.designsystem.nowPulseOnChange
import com.sagarsystemslab.nownetwork.model.ActiveRefresh
import com.sagarsystemslab.nownetwork.model.StateDetail
import com.sagarsystemslab.nownetwork.model.StateSummary

@Composable
fun StateDetailScreen(
    uiState: StateDetailUiState,
    serverNowMillis: () -> Long,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRefreshRequest: (String) -> Unit,
    history: @Composable (StateDetail) -> Unit = {},
    onViewProof: (() -> Unit)? = null,
    onActivity: (() -> Unit)? = null,
) {
    val nowMillis = rememberVisibleServerTime(serverNowMillis)

    LazyColumn(
        modifier = Modifier.testTag("screen-state-detail"),
        contentPadding = PaddingValues(
            start = NowSpacing.PageHorizontal,
            top = NowSpacing.Space2,
            end = NowSpacing.PageHorizontal,
            bottom = NowSpacing.Space8,
        ),
        verticalArrangement = Arrangement.spacedBy(NowSpacing.Space4),
    ) {
        item {
            StateDetailTopBar(onBack = onBack)
        }

        if (uiState.loading) {
            item {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = "Loading state details"
                        },
                    color = NowColors.Blue600,
                    trackColor = NowColors.Blue100,
                )
            }
        }

        if (uiState.notice != BrowseNotice.NONE) {
            item {
                BrowseNoticeCard(
                    notice = uiState.notice,
                    hasCachedContent = uiState.cachedSummary != null || uiState.detail != null,
                )
            }
        }

        val detail = uiState.detail
        val cached = uiState.cachedSummary

        when {
            detail != null -> {
                val freshness = detail.freshnessAt(nowMillis)

                item {
                    StateHero(
                        detail = detail,
                        nowMillis = nowMillis,
                    )
                }

                item { com.sagarsystemslab.nownetwork.feature.common.LocationAction(detail) }
                item { VerificationCard(detail) }
                onViewProof?.let { open -> item { com.sagarsystemslab.nownetwork.designsystem.NowGlassCard {
                    com.sagarsystemslab.nownetwork.feature.common.ExperienceRow("View authorized proof", "Evidence stays private to authorized participants", androidx.compose.material.icons.Icons.Outlined.Verified, open)
                } } }
                item { history(detail) }
                onActivity?.let { action -> item { NowSecondaryButton("View my activity", action, Modifier.fillMaxWidth()) } }

                detail.activeRefresh?.let { refresh ->
                    item {
                        ActiveRefreshCard(
                            refresh = refresh,
                            stateId = detail.stateId,
                            onRefreshRequest = onRefreshRequest,
                        )
                    }
                }

                if (freshness == FreshnessKind.CONFLICT) {
                    item {
                        NowNotice(
                            title = "Conflicting proof",
                            body = "Recent evidence does not agree yet. This state is not presented as current truth until the conflict is resolved.",
                            tone = NowNoticeTone.ERROR,
                        )
                    }
                }

                if (
                    detail.activeRefresh == null &&
                    freshness != FreshnessKind.LIVE &&
                    freshness != FreshnessKind.CONFLICT
                ) {
                    item {
                        RefreshActionCard(
                            stateId = detail.stateId,
                            freshness = freshness,
                            onRefreshRequest = onRefreshRequest,
                        )
                    }
                }

                item {
                    TechnicalDetails(detail)
                }
            }

            cached != null -> {
                item {
                    CachedStateDetail(
                        state = cached,
                        nowMillis = nowMillis,
                    )
                }
            }

            uiState.loading -> {
                item {
                    DetailLoadingCard()
                }
            }

            else -> {
                item {
                    UnavailableState(
                        onRetry = onRetry,
                    )
                }
            }
        }
    }
}

@Composable
private fun StateDetailTopBar(
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "Back",
                tint = NowColors.Ink700,
            )
        }
        Text(
            text = "State detail",
            style = NowType.TitleM,
            color = NowColors.Ink950,
            modifier = Modifier.semantics {
                heading()
            },
        )
    }
}

@Composable
private fun StateHero(
    detail: StateDetail,
    nowMillis: Long,
) {
    val freshness = detail.freshnessAt(nowMillis)
    val displayValue = formatStateValue(detail.valueJson, detail.unitCode)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("state-detail-hero"),
        shape = MaterialTheme.shapes.large,
        color = NowColors.SurfacePrimary,
        border = BorderStroke(1.dp, NowColors.BorderSubtle),
    ) {
        Column(
            modifier = Modifier.padding(NowSpacing.Space4),
            verticalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
            ) {
                FreshnessChip(
                    freshness = freshness,
                    modifier = Modifier.nowLivePulse(
                        active = freshness == FreshnessKind.LIVE,
                    ),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = relativeObservedTime(detail.observedAtMillis, nowMillis),
                    style = NowType.LabelM,
                    color = freshnessTextColor(freshness),
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(NowSpacing.Space1),
            ) {
                Text(
                    text = detail.title,
                    style = NowType.TitleXL,
                    color = NowColors.Ink950,
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space1),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.LocationOn,
                        contentDescription = null,
                        tint = NowColors.Ink500,
                        modifier = Modifier.size(17.dp),
                    )
                    Text(
                        text = buildLocationLabel(detail),
                        style = NowType.BodyS,
                        color = NowColors.Ink500,
                    )
                }
            }

            HorizontalDivider(color = NowColors.BorderSubtle)
            com.sagarsystemslab.nownetwork.feature.common.CategoryArtwork(detail.title, Modifier.size(76.dp))
            Text(
                text = displayValue,
                style = NowType.DataHero,
                color = NowColors.Ink950,
                modifier = Modifier.nowPulseOnChange(
                    key = displayValue,
                    durationMillis = com.sagarsystemslab.nownetwork.designsystem.NowMotion.StateMillis,
                ),
            )

            Text(
                text = detail.question,
                style = NowType.BodyM,
                color = NowColors.Ink600,
            )
        }
    }
}

@Composable
private fun VerificationCard(detail: StateDetail) {
    val verification = detail.verification

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("state-detail-verification"),
        shape = MaterialTheme.shapes.large,
        color = NowColors.SurfacePrimary,
        border = BorderStroke(1.dp, NowColors.BorderSubtle),
    ) {
        Column(
            modifier = Modifier.padding(NowSpacing.Space4),
            verticalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
            ) {
                Surface(
                    modifier = Modifier.size(38.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = NowColors.LiveSoft,
                    border = BorderStroke(1.dp, NowColors.LiveBorder),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Verified,
                            contentDescription = null,
                            tint = NowColors.LiveText,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "Verification",
                        style = NowType.TitleS,
                        color = NowColors.Ink950,
                    )
                    Text(
                        text = "Why this state can be trusted",
                        style = NowType.BodyS,
                        color = NowColors.Ink500,
                    )
                }
            }

            if (verification == null) {
                Text(
                    text = "Verification details are not available for this state.",
                    style = NowType.BodyM,
                    color = NowColors.Ink500,
                )
            } else {
                val reportWord = if (verification.evidenceCount == 1) "fresh report" else "fresh reports"

                VerificationRow(
                    label = humanizeStatus(verification.status),
                )
                VerificationRow(
                    label = "${verification.evidenceCount} $reportWord",
                )
                detail.verificationClass
                    ?.takeIf { it.isNotBlank() }
                    ?.let { verificationClass ->
                        VerificationRow(
                            label = humanizeStatus(verificationClass),
                        )
                    }

                if (verification.reasonCodes.isNotEmpty()) {
                    Text(
                        text = verification.reasonCodes
                            .take(3)
                            .joinToString(" · ") { humanizeStatus(it) },
                        style = NowType.BodyS,
                        color = NowColors.Ink500,
                    )
                }
            }
        }
    }
}

@Composable
private fun VerificationRow(
    label: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
    ) {
        Icon(
            imageVector = Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = NowColors.LiveText,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = NowType.BodyM,
            color = NowColors.Ink700,
        )
    }
}

@Composable
private fun ActiveRefreshCard(
    refresh: ActiveRefresh,
    stateId: String,
    onRefreshRequest: (String) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("state-detail-active-refresh"),
        shape = MaterialTheme.shapes.large,
        color = NowColors.InfoSoft,
        border = BorderStroke(1.dp, NowColors.InfoBorder),
    ) {
        Column(
            modifier = Modifier.padding(NowSpacing.Space4),
            verticalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = null,
                    tint = NowColors.InfoText,
                    modifier = Modifier.size(20.dp),
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "Refreshing now",
                        style = NowType.TitleS,
                        color = NowColors.InfoText,
                    )
                    Text(
                        text = humanizeStatus(refresh.status),
                        style = NowType.BodyM,
                        color = NowColors.Ink700,
                    )
                }
            }

            if (refresh.status == "DRAFT" || refresh.status == "AWAITING_FUNDING") {
                NowPrimaryButton(
                    text = "Continue funding",
                    onClick = { onRefreshRequest(stateId) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    text = "Fresh proof is in progress. This state will update when verification completes.",
                    style = NowType.BodyS,
                    color = NowColors.Ink600,
                )
            }
        }
    }
}

@Composable
private fun RefreshActionCard(
    stateId: String,
    freshness: FreshnessKind,
    onRefreshRequest: (String) -> Unit,
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
                    text = if (freshness == FreshnessKind.STALE) {
                        "Need current proof?"
                    } else {
                        "Refresh this state"
                    },
                    style = NowType.TitleS,
                    color = NowColors.Ink950,
                )
                Text(
                    text = if (freshness == FreshnessKind.STALE) {
                        "This value is no longer fresh. Fund a nearby contributor to verify what is true now."
                    } else {
                        "Request a new observation before this value becomes stale."
                    },
                    style = NowType.BodyM,
                    color = NowColors.Ink600,
                )
            }

            NowPrimaryButton(
                text = "Refresh this state",
                onClick = { onRefreshRequest(stateId) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("refresh-state"),
            )
        }
    }
}

@Composable
private fun TechnicalDetails(detail: StateDetail) {
    var expanded by remember { mutableStateOf(false) }

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
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (expanded) "Hide technical details" else "Technical details")
            }

            if (expanded) {
                HorizontalDivider(color = NowColors.BorderSubtle)
                TechnicalRow(
                    label = "State ID",
                    value = detail.stateId,
                )
                TechnicalRow(
                    label = "Revision",
                    value = detail.revision.toString(),
                )
                TechnicalRow(
                    label = "Canonical key",
                    value = detail.canonicalKey,
                )
            }
        }
    }
}

@Composable
private fun TechnicalRow(
    label: String,
    value: String,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = NowType.LabelM,
            color = NowColors.Ink500,
        )
        Text(
            text = value,
            style = NowType.BodyS,
            color = NowColors.Ink700,
        )
    }
}

@Composable
private fun CachedStateDetail(
    state: StateSummary,
    nowMillis: Long,
) {
    val freshness = state.freshnessAt(nowMillis)

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FreshnessChip(freshness)
                Spacer(Modifier.weight(1f))
                Text(
                    text = relativeObservedTime(state.observedAtMillis, nowMillis),
                    style = NowType.LabelM,
                    color = freshnessTextColor(freshness),
                )
            }

            Text(
                text = state.title,
                style = NowType.TitleXL,
                color = NowColors.Ink950,
            )
            Text(
                text = formatStateValue(state.valueJson, state.unitCode),
                style = NowType.DataHero,
                color = NowColors.Ink950,
            )
            Text(
                text = state.question,
                style = NowType.BodyM,
                color = NowColors.Ink600,
            )

            NowNotice(
                body = "Showing the latest state saved on this device while live detail is unavailable.",
                tone = NowNoticeTone.NEUTRAL,
            )
        }
    }
}

@Composable
private fun DetailLoadingCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = NowColors.SurfacePrimary,
        border = BorderStroke(1.dp, NowColors.BorderSubtle),
    ) {
        Column(
            modifier = Modifier.padding(NowSpacing.Space6),
            verticalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
        ) {
            Text(
                text = "Loading current state…",
                style = NowType.BodyL,
                color = NowColors.Ink500,
            )
        }
    }
}

@Composable
private fun UnavailableState(
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
                    text = "State unavailable",
                    style = NowType.TitleM,
                    color = NowColors.Ink950,
                )
                Text(
                    text = "Current state details could not be loaded.",
                    style = NowType.BodyM,
                    color = NowColors.Ink500,
                )
            }

            NowSecondaryButton(
                text = "Try again",
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun freshnessTextColor(freshness: FreshnessKind) =
    when (freshness) {
        FreshnessKind.LIVE -> NowColors.LiveText
        FreshnessKind.AGING -> NowColors.AgingText
        FreshnessKind.STALE -> NowColors.StaleText
        FreshnessKind.CONFLICT -> NowColors.ConflictText
        FreshnessKind.UNKNOWN -> NowColors.Ink500
    }

private fun buildLocationLabel(detail: StateDetail): String =
    listOfNotNull(
        detail.location.name.takeIf { it.isNotBlank() },
        detail.location.displayAddress?.takeIf { it.isNotBlank() },
    ).distinct().joinToString(" · ")
