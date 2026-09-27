package com.sagarsystemslab.nownetwork.feature.earn

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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

@Composable
fun ContributorClaimScreen(
    uiState: ContributorClaimUiState,
    rewardText: String,
    onBack: () -> Unit,
    onPrepare: () -> Unit,
    onSubmit: () -> Unit,
    onCheck: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = NowSpacing.PageHorizontal,
                vertical = NowSpacing.Space3,
            )
            .testTag("screen-contributor-claim"),
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
                text = "Claim opportunity",
                style = NowType.TitleL,
                color = NowColors.Ink950,
            )
        }

        uiState.opportunity?.let { opportunity ->
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
                        text = rewardText,
                        style = NowType.DataMedium,
                        color = NowColors.Ink950,
                    )
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
                    Text(
                        text = opportunity.location.name,
                        style = NowType.BodyS,
                        color = NowColors.Ink500,
                    )
                    Text(
                        text = buildString {
                            append(opportunity.verificationClass)
                            if (opportunity.evidenceSummary.mediaRequired) append(" · photo proof")
                            if (opportunity.evidenceSummary.locationRequired) append(" · location check")
                            append(" · ")
                            append(opportunity.availability.remainingSlots)
                            append(if (opportunity.availability.remainingSlots == 1) " slot" else " slots")
                        },
                        style = NowType.BodyS,
                        color = NowColors.Ink500,
                    )
                }
            }
        }

        when (uiState.stage) {
            ContributorClaimStage.LOADING -> {
                StatusCard(
                    title = "Loading live opportunity",
                    body = "Checking current availability and any saved claim state.",
                    progress = true,
                )
            }

            ContributorClaimStage.REVIEW -> {
                StatusCard(
                    title = "Ready to claim",
                    body = "Your wallet will be connected and bound first. No transaction is sent until the final wallet confirmation.",
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onPrepare,
                ) {
                    Text("Prepare claim")
                }
            }

            ContributorClaimStage.PREPARING -> {
                StatusCard(
                    title = "Securing claim slot",
                    body = "Validating wallet, live capacity, claim accounts, and transaction data.",
                    progress = true,
                )
            }

            ContributorClaimStage.READY_FOR_WALLET -> {
                StatusCard(
                    title = "Review before wallet",
                    body = buildString {
                        append("Wallet ")
                        append(shortAddress(uiState.walletAddress))
                        uiState.claimDurationSeconds?.let { seconds ->
                            append(" · claim window ")
                            append(formatDuration(seconds))
                        }
                        append(". Approval submits one Solana transaction. The claim is active only after authoritative confirmation.")
                    },
                )
                uiState.message?.let { InlineMessage(it) }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onSubmit,
                ) {
                    Text("Confirm in wallet")
                }
            }

            ContributorClaimStage.SUBMITTING -> {
                StatusCard(
                    title = "Submitting claim",
                    body = "Waiting for the wallet result. Do not start another claim transaction.",
                    progress = true,
                )
            }

            ContributorClaimStage.CONFIRMING -> {
                StatusCard(
                    title = "Confirmation pending",
                    body = uiState.message
                        ?: "The transaction may already be on-chain. NOW will not send a duplicate while the outcome is uncertain.",
                    progress = true,
                )
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onCheck,
                ) {
                    Text("Check status")
                }
            }

            ContributorClaimStage.CLAIMED -> {
                val claim = uiState.claim
                StatusCard(
                    title = "CLAIMED",
                    body = buildString {
                        claim?.claimSlot?.let { slot ->
                            append("Witness slot ")
                            append(slot + 1)
                            append(" confirmed. ")
                        }
                        claim?.claimDeadline?.let { deadline ->
                            append("Proof deadline: ")
                            append(deadline)
                            append(". ")
                        }
                        append("Evidence capture is the next step.")
                    },
                )
            }

            ContributorClaimStage.ERROR -> {
                StatusCard(
                    title = "Claim needs attention",
                    body = uiState.message ?: "Claim could not be completed safely.",
                )
                if (uiState.canPrepare) {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onPrepare,
                    ) {
                        Text("Try again")
                    }
                } else {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onCheck,
                    ) {
                        Text("Reconcile status")
                    }
                }
            }
        }

        Spacer(Modifier.padding(bottom = NowSpacing.Space3))
    }
}

@Composable
private fun StatusCard(
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

@Composable
private fun InlineMessage(message: String) {
    Text(
        text = message,
        style = NowType.BodyS,
        color = NowColors.Ink600,
    )
}

private fun shortAddress(address: String?): String =
    when {
        address.isNullOrBlank() -> "not connected"
        address.length <= 12 -> address
        else -> address.take(6) + "…" + address.takeLast(4)
    }

private fun formatDuration(seconds: Long): String =
    when {
        seconds % 60L == 0L -> (seconds / 60L).toString() + " min"
        else -> seconds.toString() + " sec"
    }
