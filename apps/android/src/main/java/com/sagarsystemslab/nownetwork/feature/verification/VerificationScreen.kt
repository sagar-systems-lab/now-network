package com.sagarsystemslab.nownetwork.feature.verification

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = NowSpacing.PageHorizontal,
                vertical = NowSpacing.Space3,
            )
            .testTag("screen-verification"),
        verticalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                )
            }
            Text(
                text = "Verification",
                style = NowType.TitleL,
                color = NowColors.Ink950,
            )
        }

        when (uiState.stage) {
            VerificationStage.VERIFYING -> {
                VerificationStatusCard(
                    title = "Verifying evidence",
                    body = uiState.message
                        ?: "Checking committed evidence against the frozen policy.",
                    progress = true,
                )
            }

            VerificationStage.VERIFIED -> {
                VerificationStatusCard(
                    title = if (uiState.projectionSuperseded) {
                        "Verified"
                    } else {
                        "Verified · state updated"
                    },
                    body = uiState.message ?: "Verification completed.",
                )
                VerificationFacts(uiState)

                uiState.projectedValue?.let { value ->
                    VerificationStatusCard(
                        title = "Live state",
                        body = buildString {
                            append(displayJson(value))
                            uiState.projectedFreshness?.let {
                                append(" · ")
                                append(it)
                            }
                            uiState.projectedStateRevision?.let {
                                append(" · revision ")
                                append(it)
                            }
                        },
                    )
                }

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onTrackPayment,
                ) {
                    Text("Track payment")
                }
            }

            VerificationStage.MORE_EVIDENCE -> {
                VerificationStatusCard(
                    title = "More evidence needed",
                    body = uiState.message ?: "Verification needs another evidence set.",
                )
                VerificationFacts(uiState)
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDone,
                ) {
                    Text("Back to Earn")
                }
            }

            VerificationStage.CONFLICT -> {
                VerificationStatusCard(
                    title = "Reports conflict",
                    body = uiState.message ?: "No live result will be published yet.",
                )
                VerificationFacts(uiState)
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDone,
                ) {
                    Text("Back to Earn")
                }
            }

            VerificationStage.REJECTED -> {
                VerificationStatusCard(
                    title = "Evidence not verified",
                    body = uiState.message ?: "Verification rejected this evidence set.",
                )
                VerificationFacts(uiState)
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDone,
                ) {
                    Text("Done")
                }
            }

            VerificationStage.EXPIRED -> {
                VerificationStatusCard(
                    title = "Refresh expired",
                    body = uiState.message ?: "Verification can no longer complete.",
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDone,
                ) {
                    Text("Done")
                }
            }

            VerificationStage.ERROR -> {
                VerificationStatusCard(
                    title = "Verification needs attention",
                    body = uiState.message
                        ?: "The authoritative result could not be confirmed.",
                )
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onRetry,
                ) {
                    Text("Check again")
                }
            }
        }

        Spacer(Modifier.height(NowSpacing.Space3))
    }
}

@Composable
private fun VerificationFacts(uiState: VerificationUiState) {
    VerificationStatusCard(
        title = "Evidence set",
        body = buildString {
            append(uiState.evidenceCount)
            append(" committed packet")
            if (uiState.evidenceCount != 1) append("s")
            if (uiState.evidenceSetRevision > 0) {
                append(" · revision ")
                append(uiState.evidenceSetRevision)
            }
        },
    )

    VerificationStatusCard(
        title = "Policy",
        body = "Verification policy v${uiState.policyVersion}",
    )

    uiState.finalAnswer?.let { answer ->
        VerificationStatusCard(
            title = "Verified answer",
            body = displayJson(answer),
        )
    }

    if (uiState.reasonCodes.isNotEmpty()) {
        VerificationStatusCard(
            title = "Reason",
            body = uiState.reasonCodes.joinToString(" · ") { humanize(it) },
        )
    }

    if (uiState.replayed) {
        Text(
            text = "Recovered from the same authoritative verification result.",
            style = NowType.BodyS,
            color = NowColors.Ink500,
        )
    }
}

@Composable
private fun VerificationStatusCard(
    title: String,
    body: String,
    progress: Boolean = false,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = NowColors.SurfacePrimary,
        border = BorderStroke(1.dp, NowColors.BorderSubtle),
    ) {
        Row(
            modifier = Modifier.padding(NowSpacing.Space4),
            horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
            verticalAlignment = Alignment.Top,
        ) {
            if (progress) {
                CircularProgressIndicator()
            }
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
