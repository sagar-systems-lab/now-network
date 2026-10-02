package com.sagarsystemslab.nownetwork.feature.earn

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Schedule
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
import com.sagarsystemslab.nownetwork.designsystem.NowNotice
import com.sagarsystemslab.nownetwork.designsystem.NowNoticeTone
import com.sagarsystemslab.nownetwork.designsystem.NowPrimaryButton
import com.sagarsystemslab.nownetwork.designsystem.NowSecondaryButton
import com.sagarsystemslab.nownetwork.designsystem.NowSpacing
import com.sagarsystemslab.nownetwork.designsystem.NowStatusChip
import com.sagarsystemslab.nownetwork.designsystem.NowStatusTone
import com.sagarsystemslab.nownetwork.designsystem.NowType
import com.sagarsystemslab.nownetwork.network.OpportunityDto

@Composable
fun ContributorClaimScreen(
    uiState: ContributorClaimUiState,
    rewardText: String,
    onBack: () -> Unit,
    onPrepare: () -> Unit,
    onSubmit: () -> Unit,
    onCheck: () -> Unit,
    onCaptureEvidence: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = NowSpacing.PageHorizontal,
                vertical = NowSpacing.Space2,
            )
            .testTag("screen-contributor-claim"),
        verticalArrangement = Arrangement.spacedBy(NowSpacing.Space4),
    ) {
        ClaimTopBar(onBack = onBack)

        uiState.opportunity?.let { opportunity ->
            OpportunityDetailCard(
                opportunity = opportunity,
                rewardText = rewardText,
            )
            ProofRequirementsCard(opportunity)
        }

        when (uiState.stage) {
            ContributorClaimStage.LOADING -> {
                ProcessCard(
                    icon = Icons.Outlined.Schedule,
                    title = "Checking live availability",
                    body = "Confirming the task is still open and recovering any unfinished claim.",
                    progress = true,
                )
            }

            ContributorClaimStage.REVIEW -> {
                NowNotice(
                    title = "Before you claim",
                    body = "Review the task and proof requirements above. Your wallet is connected only after you continue.",
                    tone = NowNoticeTone.NEUTRAL,
                )
                NowPrimaryButton(
                    text = "Prepare claim",
                    onClick = onPrepare,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("prepare-claim"),
                )
            }

            ContributorClaimStage.PREPARING -> {
                ProcessCard(
                    icon = Icons.Outlined.AccountBalanceWallet,
                    title = "Preparing claim",
                    body = "Checking wallet binding, live capacity, claim accounts, and transaction data. Nothing has been sent yet.",
                    progress = true,
                )
            }

            ContributorClaimStage.READY_FOR_WALLET -> {
                WalletReviewCard(
                    walletAddress = uiState.walletAddress,
                    claimDurationSeconds = uiState.claimDurationSeconds,
                )
                uiState.message?.let { message ->
                    NowNotice(
                        body = message,
                        tone = NowNoticeTone.NEUTRAL,
                    )
                }
                NowPrimaryButton(
                    text = "Confirm in wallet",
                    onClick = onSubmit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("confirm-claim-wallet"),
                )
            }

            ContributorClaimStage.SUBMITTING -> {
                NowNotice(
                    title = "Wallet transaction in progress",
                    body = "Do not start another claim transaction while this result is unknown.",
                    tone = NowNoticeTone.INFO,
                )
                ProcessCard(
                    icon = Icons.Outlined.AccountBalanceWallet,
                    title = "Waiting for wallet",
                    body = "NOW will move forward only after the wallet result can be reconciled safely.",
                    progress = true,
                )
            }

            ContributorClaimStage.CONFIRMING -> {
                NowNotice(
                    title = "Checking previous transaction",
                    body = "Do not submit again. The claim may already be on-chain and NOW is reconciling the existing operation.",
                    tone = NowNoticeTone.INFO,
                )
                ProcessCard(
                    icon = Icons.Outlined.Schedule,
                    title = "Confirming claim",
                    body = uiState.message
                        ?: "Waiting for authoritative claim confirmation before enabling evidence capture.",
                    progress = true,
                )
                NowSecondaryButton(
                    text = "Check existing claim",
                    onClick = onCheck,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            ContributorClaimStage.CLAIMED -> {
                ClaimedCard(
                    uiState = uiState,
                    onCaptureEvidence = onCaptureEvidence,
                )
            }

            ContributorClaimStage.ERROR -> {
                NowNotice(
                    title = "Claim needs attention",
                    body = uiState.message ?: "The claim could not be completed safely.",
                    tone = NowNoticeTone.ERROR,
                )
                if (uiState.canPrepare) {
                    NowPrimaryButton(
                        text = "Try again",
                        onClick = onPrepare,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    NowSecondaryButton(
                        text = "Reconcile claim status",
                        onClick = onCheck,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        Spacer(Modifier.padding(bottom = NowSpacing.Space3))
    }
}

@Composable
private fun ClaimTopBar(
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
            text = "Opportunity",
            style = NowType.TitleM,
            color = NowColors.Ink950,
            modifier = Modifier.semantics {
                heading()
            },
        )
    }
}

@Composable
private fun OpportunityDetailCard(
    opportunity: OpportunityDto,
    rewardText: String,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("opportunity-detail"),
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
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "REWARD",
                        style = NowType.LabelM,
                        color = NowColors.Ink500,
                    )
                    Text(
                        text = rewardText,
                        style = NowType.DataMedium,
                        color = NowColors.Ink950,
                    )
                }

                NowStatusChip(
                    label = if (opportunity.availability.claimable) {
                        "AVAILABLE"
                    } else {
                        "FILLED"
                    },
                    tone = if (opportunity.availability.claimable) {
                        NowStatusTone.LIVE
                    } else {
                        NowStatusTone.STALE
                    },
                    accessibilityLabel = if (opportunity.availability.claimable) {
                        "Opportunity available"
                    } else {
                        "Opportunity filled"
                    },
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(NowSpacing.Space1),
            ) {
                Text(
                    text = opportunity.title,
                    style = NowType.TitleL,
                    color = NowColors.Ink950,
                )
                Text(
                    text = opportunity.question,
                    style = NowType.BodyM,
                    color = NowColors.Ink600,
                )
            }

            HorizontalDivider(color = NowColors.BorderSubtle)

            DetailRow(
                icon = Icons.Outlined.LocationOn,
                label = buildLocationLabel(opportunity),
            )
            DetailRow(
                icon = Icons.Outlined.Schedule,
                label = "Opportunity closes " + readableDeadline(opportunity.expiresAt),
                emphasized = true,
            )

            Text(
                text = if (opportunity.availability.remainingSlots == 1) {
                    "1 contributor slot remaining"
                } else {
                    opportunity.availability.remainingSlots.toString() +
                        " contributor slots remaining"
                },
                style = NowType.BodyS,
                color = NowColors.Ink500,
            )
        }
    }
}

@Composable
private fun ProofRequirementsCard(
    opportunity: OpportunityDto,
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
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "Proof required",
                    style = NowType.TitleS,
                    color = NowColors.Ink950,
                )
                Text(
                    text = "Know exactly what must be captured before you claim.",
                    style = NowType.BodyS,
                    color = NowColors.Ink500,
                )
            }

            RequirementRow(
                icon = Icons.Outlined.Verified,
                title = humanizeRequirement(opportunity.verificationClass),
                body = "Verification policy for this refresh",
            )

            if (opportunity.evidenceSummary.mediaRequired) {
                RequirementRow(
                    icon = Icons.Outlined.CameraAlt,
                    title = "Fresh photo",
                    body = "Capture evidence after the claim is confirmed",
                )
            }

            if (opportunity.evidenceSummary.locationRequired) {
                RequirementRow(
                    icon = Icons.Outlined.LocationOn,
                    title = "Location match",
                    body = "Location evidence is checked against the task",
                )
            }

            RequirementRow(
                icon = Icons.Outlined.Schedule,
                title = "Evidence deadline",
                body = readableDeadline(opportunity.evidenceDeadline),
            )

            Text(
                text = buildString {
                    append(opportunity.evidenceSummary.requiredWitnesses)
                    append(
                        if (opportunity.evidenceSummary.requiredWitnesses == 1) {
                            " verified contributor required"
                        } else {
                            " verified contributors required"
                        },
                    )
                },
                style = NowType.BodyS,
                color = NowColors.Ink500,
            )
        }
    }
}

@Composable
private fun DetailRow(
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
private fun RequirementRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = MaterialTheme.shapes.medium,
            color = NowColors.Blue50,
            border = BorderStroke(1.dp, NowColors.Blue100),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = NowColors.Blue600,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = NowType.LabelL,
                color = NowColors.Ink800,
            )
            Text(
                text = body,
                style = NowType.BodyS,
                color = NowColors.Ink500,
            )
        }
    }
}

@Composable
private fun WalletReviewCard(
    walletAddress: String?,
    claimDurationSeconds: Long?,
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
            ) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    color = NowColors.Blue50,
                    border = BorderStroke(1.dp, NowColors.Blue100),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.AccountBalanceWallet,
                            contentDescription = null,
                            tint = NowColors.Blue600,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "Review before wallet",
                        style = NowType.TitleS,
                        color = NowColors.Ink950,
                    )
                    Text(
                        text = "One claim transaction will be requested.",
                        style = NowType.BodyS,
                        color = NowColors.Ink500,
                    )
                }
            }

            HorizontalDivider(color = NowColors.BorderSubtle)

            ReviewRow(
                label = "Wallet",
                value = shortAddress(walletAddress),
            )
            claimDurationSeconds?.let { seconds ->
                ReviewRow(
                    label = "Claim window",
                    value = formatDuration(seconds),
                )
            }

            NowNotice(
                body = "The claim is active only after authoritative confirmation. A wallet callback alone is not treated as success.",
                tone = NowNoticeTone.NEUTRAL,
            )
        }
    }
}

@Composable
private fun ReviewRow(
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
private fun ClaimedCard(
    uiState: ContributorClaimUiState,
    onCaptureEvidence: () -> Unit,
) {
    val claim = uiState.claim

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("claim-confirmed"),
        shape = MaterialTheme.shapes.large,
        color = NowColors.LiveSoft,
        border = BorderStroke(1.dp, NowColors.LiveBorder),
    ) {
        Column(
            modifier = Modifier.padding(NowSpacing.Space4),
            verticalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
        ) {
            Surface(
                modifier = Modifier.size(46.dp),
                shape = CircleShape,
                color = NowColors.SurfacePrimary,
                border = BorderStroke(1.dp, NowColors.LiveBorder),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = NowColors.LiveText,
                        modifier = Modifier.size(25.dp),
                    )
                }
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(NowSpacing.Space1),
            ) {
                Text(
                    text = "Claim confirmed",
                    style = NowType.TitleL,
                    color = NowColors.LiveText,
                )
                Text(
                    text = "This task is reserved for you. Capture the required evidence before the deadline.",
                    style = NowType.BodyM,
                    color = NowColors.Ink700,
                )
            }

            claim?.claimSlot?.let { slot ->
                ReviewRow(
                    label = "Witness slot",
                    value = (slot + 1).toString(),
                )
            }
            claim?.claimDeadline?.let { deadline ->
                ReviewRow(
                    label = "Claim deadline",
                    value = readableDeadline(deadline),
                )
            }
            claim?.evidenceDeadline?.let { deadline ->
                ReviewRow(
                    label = "Evidence deadline",
                    value = readableDeadline(deadline),
                )
            }

            if (claim != null) {
                NowPrimaryButton(
                    text = "Capture evidence",
                    onClick = onCaptureEvidence,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("capture-evidence"),
                )
            }
        }
    }
}

@Composable
private fun ProcessCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    progress: Boolean,
) {
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
            if (progress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = NowColors.Blue600,
                    trackColor = NowColors.Blue100,
                    strokeWidth = 3.dp,
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = NowColors.Blue600,
                    modifier = Modifier.size(24.dp),
                )
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

private fun buildLocationLabel(opportunity: OpportunityDto): String =
    listOfNotNull(
        opportunity.location.name.takeIf(String::isNotBlank),
        opportunity.location.displayAddress?.takeIf(String::isNotBlank),
    )
        .distinct()
        .joinToString(" · ")

private fun readableDeadline(value: String): String =
    value
        .replace("T", " ")
        .removeSuffix("Z") + " UTC"

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

private fun shortAddress(address: String?): String =
    when {
        address.isNullOrBlank() -> "Not connected"
        address.length <= 12 -> address
        else -> address.take(6) + "…" + address.takeLast(4)
    }

private fun formatDuration(seconds: Long): String =
    when {
        seconds % 60L == 0L -> (seconds / 60L).toString() + " min"
        else -> seconds.toString() + " sec"
    }
