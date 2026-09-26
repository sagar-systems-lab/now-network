package com.sagarsystemslab.nownetwork.feature.state

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sagarsystemslab.nownetwork.designsystem.NowColors
import com.sagarsystemslab.nownetwork.designsystem.NowSpacing
import com.sagarsystemslab.nownetwork.designsystem.NowType
import com.sagarsystemslab.nownetwork.model.StateDetail
import com.sagarsystemslab.nownetwork.model.StateSummary

@Composable
fun StateDetailScreen(
    uiState: StateDetailUiState,
    serverNowMillis: () -> Long,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    val nowMillis = rememberVisibleServerTime(serverNowMillis)

    LazyColumn(
        modifier = Modifier.testTag("screen-state-detail"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = NowSpacing.PageHorizontal,
            top = NowSpacing.Space2,
            end = NowSpacing.PageHorizontal,
            bottom = NowSpacing.Space6,
        ),
        verticalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back",
                        tint = NowColors.Ink700,
                    )
                }
            }
        }

        if (uiState.loading) {
            item {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
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
                item {
                    StateDetailHeader(
                        detail = detail,
                        nowMillis = nowMillis,
                    )
                }
                item {
                    VerificationCard(detail)
                }
                detail.activeRefresh?.let { refresh ->
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large,
                            color = NowColors.InfoSoft,
                            border = BorderStroke(1.dp, NowColors.InfoBorder),
                        ) {
                            Column(
                                modifier = Modifier.padding(NowSpacing.Space4),
                                verticalArrangement = Arrangement.spacedBy(NowSpacing.Space1),
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
                                text = "State unavailable",
                                style = NowType.TitleM,
                                color = NowColors.Ink950,
                            )
                            Text(
                                text = "This state could not be loaded.",
                                style = NowType.BodyM,
                                color = NowColors.Ink500,
                            )
                            TextButton(onClick = onRetry) {
                                Text("Try again")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StateDetailHeader(
    detail: StateDetail,
    nowMillis: Long,
) {
    val freshness = detail.freshnessAt(nowMillis)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
    ) {
        FreshnessChip(freshness)

        Text(
            text = detail.title,
            style = NowType.TitleXL,
            color = NowColors.Ink950,
        )

        Text(
            text = buildLocationLabel(detail),
            style = NowType.BodyM,
            color = NowColors.Ink500,
        )

        Text(
            text = formatStateValue(detail.valueJson, detail.unitCode),
            style = NowType.DataHero,
            color = NowColors.Ink950,
        )

        Text(
            text = relativeObservedTime(detail.observedAtMillis, nowMillis),
            style = NowType.LabelL,
            color = when (freshness) {
                FreshnessKind.LIVE -> NowColors.LiveText
                FreshnessKind.AGING -> NowColors.AgingText
                FreshnessKind.STALE -> NowColors.StaleText
                FreshnessKind.CONFLICT -> NowColors.ConflictText
                FreshnessKind.UNKNOWN -> NowColors.Ink500
            },
        )

        Text(
            text = detail.question,
            style = NowType.BodyM,
            color = NowColors.Ink600,
        )
    }
}

@Composable
private fun VerificationCard(detail: StateDetail) {
    val verification = detail.verification

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
                text = "Verification",
                style = NowType.TitleS,
                color = NowColors.Ink950,
            )

            if (verification == null) {
                Text(
                    text = "Verification details are not available for this state.",
                    style = NowType.BodyM,
                    color = NowColors.Ink500,
                )
            } else {
                val reportWord = if (verification.evidenceCount == 1) "report" else "reports"
                Text(
                    text = "${detail.verificationClass ?: verification.status} · ${verification.evidenceCount} fresh $reportWord",
                    style = NowType.BodyL,
                    color = NowColors.Ink800,
                )

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
                Text(
                    text = "State ID · ${detail.stateId}",
                    style = NowType.BodyS,
                    color = NowColors.Ink500,
                )
                Text(
                    text = "Revision · ${detail.revision}",
                    style = NowType.BodyS,
                    color = NowColors.Ink500,
                )
                Text(
                    text = detail.canonicalKey,
                    style = NowType.BodyS,
                    color = NowColors.Ink500,
                )
            }
        }
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
            verticalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
        ) {
            FreshnessChip(freshness)
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
                text = relativeObservedTime(state.observedAtMillis, nowMillis),
                style = NowType.LabelL,
                color = NowColors.Ink600,
            )
            Text(
                text = "Showing saved state while live detail is unavailable.",
                style = NowType.BodyM,
                color = NowColors.Ink500,
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

private fun buildLocationLabel(detail: StateDetail): String =
    listOfNotNull(
        detail.location.name.takeIf { it.isNotBlank() },
        detail.location.displayAddress?.takeIf { it.isNotBlank() },
    ).distinct().joinToString(" · ")
