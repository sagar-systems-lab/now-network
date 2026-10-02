package com.sagarsystemslab.nownetwork.feature.verification

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
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
import com.sagarsystemslab.nownetwork.designsystem.nowPulseOnChange
import com.sagarsystemslab.nownetwork.designsystem.rememberNowMotionEnabled
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Composable
fun VerificationScreen(
    uiState: VerificationUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onTrackPayment: () -> Unit,
    onDone: () -> Unit,
) {
    val motionEnabled = rememberNowMotionEnabled()
    val transitionDuration = nowMotionDuration(
        enabled = motionEnabled,
        durationMillis = NowMotion.VerifyMillis,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = NowSpacing.PageHorizontal,
                vertical = NowSpacing.Space2,
            )
            .testTag("screen-verification"),
        verticalArrangement = Arrangement.spacedBy(NowSpacing.Space4),
    ) {
        VerificationTopBar(onBack = onBack)

        Crossfade(
            targetState = uiState.stage,
            animationSpec = tween(transitionDuration),
            label = "verification-stage",
        ) { stage ->
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(NowSpacing.Space4),
            ) {
                when (stage) {
                    VerificationStage.VERIFYING -> {
                        VerifyingCard(uiState)
                    }

            VerificationStage.VERIFIED -> {
                VerifiedResult(
                    uiState = uiState,
                    onTrackPayment = onTrackPayment,
                )
            }

            VerificationStage.MORE_EVIDENCE -> {
                ResultNotice(
                    title = "More evidence needed",
                    body = uiState.message
                        ?: "The current evidence set is valid but not sufficient yet.",
                    tone = NowNoticeTone.WARNING,
                )
                VerificationFacts(uiState)
                NowSecondaryButton(
                    text = "Back to Earn",
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            VerificationStage.CONFLICT -> {
                ResultNotice(
                    title = "Reports conflict",
                    body = uiState.message
                        ?: "Valid reports disagree. No live result is published and payment is not settled.",
                    tone = NowNoticeTone.ERROR,
                )
                VerificationFacts(uiState)
                NowSecondaryButton(
                    text = "Back to Earn",
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            VerificationStage.REJECTED -> {
                ResultNotice(
                    title = "Evidence not verified",
                    body = uiState.message
                        ?: "This evidence set did not produce a policy-valid result.",
                    tone = NowNoticeTone.ERROR,
                )
                VerificationFacts(uiState)
                NowSecondaryButton(
                    text = "Done",
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            VerificationStage.EXPIRED -> {
                ResultNotice(
                    title = "Refresh expired",
                    body = uiState.message
                        ?: "The refresh expired before verification could complete.",
                    tone = NowNoticeTone.ERROR,
                )
                NowSecondaryButton(
                    text = "Done",
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

                    VerificationStage.ERROR -> {
                        ResultNotice(
                            title = "Verification needs attention",
                            body = uiState.message
                                ?: "The authoritative verification result could not be confirmed safely.",
                            tone = NowNoticeTone.ERROR,
                        )
                        NowSecondaryButton(
                            text = "Check again",
                            onClick = onRetry,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(NowSpacing.Space3))
    }
}

@Composable
private fun VerificationTopBar(
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
            text = "Verification",
            style = NowType.TitleM,
            color = NowColors.Ink950,
            modifier = Modifier.semantics {
                heading()
            },
        )
    }
}

@Composable
private fun VerifyingCard(
    uiState: VerificationUiState,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("verification-progress"),
        shape = MaterialTheme.shapes.large,
        color = NowColors.InfoSoft,
        border = BorderStroke(1.dp, NowColors.InfoBorder),
    ) {
        Column(
            modifier = Modifier.padding(NowSpacing.Space5),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(NowSpacing.Space4),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(52.dp),
                color = NowColors.Blue600,
                trackColor = NowColors.Blue100,
                strokeWidth = 4.dp,
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(NowSpacing.Space1),
            ) {
                Text(
                    text = "Verifying evidence",
                    style = NowType.TitleL,
                    color = NowColors.Ink950,
                )
                Text(
                    text = uiState.message
                        ?: "Checking the committed evidence against the frozen verification policy.",
                    style = NowType.BodyM,
                    color = NowColors.Ink600,
                )
            }

            HorizontalDivider(color = NowColors.InfoBorder)

            VerificationCheck(
                label = "Evidence committed",
                complete = uiState.evidenceCount > 0,
            )
            VerificationCheck(
                label = "Policy checks",
                complete = false,
            )
            VerificationCheck(
                label = "Live state projection",
                complete = false,
            )
        }
    }
}

@Composable
private fun VerificationCheck(
    label: String,
    complete: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (complete) {
                Icons.Outlined.CheckCircle
            } else {
                Icons.Outlined.HourglassTop
            },
            contentDescription = null,
            tint = if (complete) NowColors.LiveText else NowColors.InfoText,
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
private fun VerifiedResult(
    uiState: VerificationUiState,
    onTrackPayment: () -> Unit,
) {
    if (uiState.projectionSuperseded) {
        NowNotice(
            title = "Evidence verified",
            body = uiState.message
                ?: "A newer observation already superseded this state projection.",
            tone = NowNoticeTone.SUCCESS,
        )
    } else {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("verification-live-result"),
            shape = MaterialTheme.shapes.large,
            color = NowColors.LiveSoft,
            border = BorderStroke(1.dp, NowColors.LiveBorder),
        ) {
            Column(
                modifier = Modifier.padding(NowSpacing.Space5),
                verticalArrangement = Arrangement.spacedBy(NowSpacing.Space4),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
                ) {
                    Surface(
                        modifier = Modifier.size(46.dp),
                        shape = CircleShape,
                        color = NowColors.SurfacePrimary,
                        border = BorderStroke(1.dp, NowColors.LiveBorder),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.Verified,
                                contentDescription = null,
                                tint = NowColors.LiveText,
                                modifier = Modifier.size(25.dp),
                            )
                        }
                    }

                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        NowStatusChip(
                            label = "LIVE",
                            tone = NowStatusTone.LIVE,
                            accessibilityLabel = "Verified state is live",
                        )
                        Text(
                            text = "State updated",
                            style = NowType.TitleL,
                            color = NowColors.Ink950,
                        )
                    }
                }

                uiState.projectedValue?.let { value ->
                    val displayValue = displayJson(value)
                    Text(
                        text = displayValue,
                        style = NowType.DataHero,
                        color = NowColors.Ink950,
                        modifier = Modifier.nowPulseOnChange(
                            key = uiState.projectedStateRevision ?: displayValue,
                            durationMillis = NowMotion.StateMillis,
                            pulseOnInitial = true,
                        ),
                    )
                }

                Text(
                    text = uiState.message
                        ?: "Evidence verified and the live state projection is confirmed.",
                    style = NowType.BodyM,
                    color = NowColors.Ink700,
                )

                uiState.projectedStateRevision?.let { revision ->
                    Text(
                        text = "State revision " + revision,
                        style = NowType.BodyS,
                        color = NowColors.Ink500,
                    )
                }
            }
        }
    }

    VerificationFacts(uiState)

    NowPrimaryButton(
        text = "Track payment",
        onClick = onTrackPayment,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun VerificationFacts(
    uiState: VerificationUiState,
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
            Text(
                text = "Verification details",
                style = NowType.TitleS,
                color = NowColors.Ink950,
            )

            FactRow(
                label = "Evidence",
                value = buildString {
                    append(uiState.evidenceCount)
                    append(if (uiState.evidenceCount == 1) " packet" else " packets")
                },
            )

            if (uiState.policyVersion > 0) {
                FactRow(
                    label = "Policy",
                    value = "v" + uiState.policyVersion,
                )
            }

            uiState.finalAnswer?.let { answer ->
                FactRow(
                    label = "Verified answer",
                    value = displayJson(answer),
                )
            }

            uiState.projectedFreshness?.let { freshness ->
                FactRow(
                    label = "Freshness",
                    value = humanize(freshness),
                )
            }

            if (uiState.reasonCodes.isNotEmpty()) {
                HorizontalDivider(color = NowColors.BorderSubtle)
                Text(
                    text = uiState.reasonCodes
                        .joinToString(" · ") { humanize(it) },
                    style = NowType.BodyS,
                    color = NowColors.Ink500,
                )
            }

            if (uiState.replayed) {
                NowNotice(
                    body = "Recovered from the same authoritative verification result.",
                    tone = NowNoticeTone.NEUTRAL,
                )
            }
        }
    }
}

@Composable
private fun FactRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space4),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = NowType.BodyM,
            color = NowColors.Ink500,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = NowType.LabelL,
            color = NowColors.Ink800,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ResultNotice(
    title: String,
    body: String,
    tone: NowNoticeTone,
) {
    val iconTint = when (tone) {
        NowNoticeTone.SUCCESS -> NowColors.LiveText
        NowNoticeTone.WARNING -> NowColors.AgingText
        NowNoticeTone.ERROR -> NowColors.ConflictText
        NowNoticeTone.INFO -> NowColors.InfoText
        NowNoticeTone.NEUTRAL -> NowColors.Ink500
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = NowColors.SurfacePrimary,
        border = BorderStroke(1.dp, NowColors.BorderSubtle),
    ) {
        Row(
            modifier = Modifier.padding(NowSpacing.Space4),
            horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = if (tone == NowNoticeTone.ERROR) {
                    Icons.Outlined.ErrorOutline
                } else {
                    Icons.Outlined.Verified
                },
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NowSpacing.Space1),
            ) {
                Text(
                    text = title,
                    style = NowType.TitleS,
                    color = NowColors.Ink950,
                )
                Text(
                    text = body,
                    style = NowType.BodyM,
                    color = NowColors.Ink600,
                )
            }
        }
    }
}

private fun humanize(value: String): String =
    value.lowercase()
        .replace('_', ' ')
        .replaceFirstChar { character ->
            if (character.isLowerCase()) character.titlecase() else character.toString()
        }

private fun displayJson(value: JsonElement): String =
    when (value) {
        JsonNull -> "—"
        is JsonPrimitive -> value.contentOrNull ?: value.toString()
        else -> value.toString()
    }
