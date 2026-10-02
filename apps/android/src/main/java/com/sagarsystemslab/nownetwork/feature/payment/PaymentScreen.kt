package com.sagarsystemslab.nownetwork.feature.payment

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
import androidx.compose.material.icons.outlined.Payments
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

@Composable
fun PaymentScreen(
    uiState: PaymentUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onViewReceipt: () -> Unit,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = NowSpacing.PageHorizontal,
                vertical = NowSpacing.Space2,
            )
            .testTag("screen-payment"),
        verticalArrangement = Arrangement.spacedBy(NowSpacing.Space4),
    ) {
        PaymentTopBar(onBack = onBack)

        when (uiState.stage) {
            PaymentStage.PENDING -> {
                SettlementProgressCard(
                    title = "Payment pending",
                    body = uiState.message,
                    status = "PENDING",
                    icon = Icons.Outlined.Schedule,
                )
                NowNotice(
                    title = "No action required",
                    body = "Settlement reconciliation continues even if you leave this screen.",
                    tone = NowNoticeTone.NEUTRAL,
                )
            }

            PaymentStage.VERIFYING -> {
                SettlementProgressCard(
                    title = "Checking settlement",
                    body = uiState.message,
                    status = "VERIFYING",
                    icon = Icons.Outlined.Verified,
                )
                NowNotice(
                    title = "Do not retry manually",
                    body = "NOW is checking the existing Solana settlement before any retry is allowed.",
                    tone = NowNoticeTone.INFO,
                )
            }

            PaymentStage.PAID -> {
                PaidCard(uiState)
                SettlementAuthorityCard(uiState)
                NowPrimaryButton(
                    text = "View final receipt",
                    onClick = onViewReceipt,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("view-final-receipt"),
                )
            }

            PaymentStage.FAILED -> {
                SettlementFailureCard(uiState)
                SettlementAuthorityCard(uiState)
                NowSecondaryButton(
                    text = "View activity",
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            PaymentStage.ATTENTION -> {
                NowNotice(
                    title = "Payment status unavailable",
                    body = uiState.message,
                    tone = NowNoticeTone.ERROR,
                )
                NowSecondaryButton(
                    text = "Check again",
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(NowSpacing.Space3))
    }
}

@Composable
private fun PaymentTopBar(
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
            text = "Payment",
            style = NowType.TitleM,
            color = NowColors.Ink950,
        )
    }
}

@Composable
private fun SettlementProgressCard(
    title: String,
    body: String,
    status: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
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
                modifier = Modifier.size(48.dp),
                color = NowColors.Blue600,
                trackColor = NowColors.Blue100,
                strokeWidth = 4.dp,
            )
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = NowColors.InfoText,
                modifier = Modifier.size(24.dp),
            )
            NowStatusChip(
                label = status,
                tone = NowStatusTone.INFO,
                accessibilityLabel = "Payment " + status.lowercase(),
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(NowSpacing.Space1),
            ) {
                Text(
                    text = title,
                    style = NowType.TitleL,
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
private fun PaidCard(
    uiState: PaymentUiState,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("payment-paid"),
        shape = MaterialTheme.shapes.large,
        color = NowColors.LiveSoft,
        border = BorderStroke(1.dp, NowColors.LiveBorder),
    ) {
        Column(
            modifier = Modifier.padding(NowSpacing.Space5),
            verticalArrangement = Arrangement.spacedBy(NowSpacing.Space4),
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                color = NowColors.SurfacePrimary,
                border = BorderStroke(1.dp, NowColors.LiveBorder),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = NowColors.LiveText,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }

            NowStatusChip(
                label = "PAID",
                tone = NowStatusTone.LIVE,
                accessibilityLabel = "Payment paid",
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(NowSpacing.Space1),
            ) {
                Text(
                    text = if (uiState.chainCommitment == "finalized") {
                        "Payment finalized"
                    } else {
                        "Payment confirmed"
                    },
                    style = NowType.TitleL,
                    color = NowColors.Ink950,
                )
                Text(
                    text = uiState.message,
                    style = NowType.BodyM,
                    color = NowColors.Ink700,
                )
            }

            (uiState.finalizedAt ?: uiState.confirmedAt)?.let { timestamp ->
                Text(
                    text = "Authority time · " + readableTime(timestamp),
                    style = NowType.BodyS,
                    color = NowColors.Ink500,
                )
            }
        }
    }
}

@Composable
private fun SettlementFailureCard(
    uiState: PaymentUiState,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = NowColors.ConflictSoft,
        border = BorderStroke(1.dp, NowColors.ConflictBorder),
    ) {
        Row(
            modifier = Modifier.padding(NowSpacing.Space4),
            horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = NowColors.ConflictText,
                modifier = Modifier.size(24.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NowSpacing.Space1),
            ) {
                Text(
                    text = "Settlement stopped safely",
                    style = NowType.TitleS,
                    color = NowColors.ConflictText,
                )
                Text(
                    text = uiState.message,
                    style = NowType.BodyM,
                    color = NowColors.Ink700,
                )
            }
        }
    }
}

@Composable
private fun SettlementAuthorityCard(
    uiState: PaymentUiState,
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
                Icon(
                    imageVector = Icons.Outlined.Payments,
                    contentDescription = null,
                    tint = NowColors.Blue600,
                    modifier = Modifier.size(21.dp),
                )
                Text(
                    text = "Settlement authority",
                    style = NowType.TitleS,
                    color = NowColors.Ink950,
                )
            }

            HorizontalDivider(color = NowColors.BorderSubtle)

            AuthorityRow(
                label = "Status",
                value = humanize(uiState.settlementStatus),
            )
            uiState.chainCommitment?.let {
                AuthorityRow(
                    label = "Commitment",
                    value = humanize(it),
                )
            }
            uiState.chainSignature?.let {
                AuthorityRow(
                    label = "Signature",
                    value = shorten(it),
                )
            }
        }
    }
}

@Composable
private fun AuthorityRow(
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
        )
    }
}

private fun shorten(value: String): String =
    if (value.length <= 18) value else value.take(8) + "…" + value.takeLast(8)

private fun humanize(value: String): String =
    value.lowercase()
        .replace('_', ' ')
        .replaceFirstChar { character ->
            if (character.isLowerCase()) character.titlecase() else character.toString()
        }

private fun readableTime(value: String): String =
    value.replace("T", " ").removeSuffix("Z") + " UTC"
