package com.sagarsystemslab.nownetwork.feature.receipt

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
import androidx.compose.material.icons.outlined.ReceiptLong
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
import com.sagarsystemslab.nownetwork.repository.FinalReceipt
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Composable
fun ReceiptScreen(
    uiState: ReceiptUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
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
            .testTag("screen-receipt"),
        verticalArrangement = Arrangement.spacedBy(NowSpacing.Space4),
    ) {
        ReceiptTopBar(onBack = onBack)

        when (uiState.stage) {
            ReceiptStage.FINALIZING -> {
                FinalizingReceiptCard(uiState.message)
                NowNotice(
                    body = "No payment action is required. The receipt becomes final only after settlement authority is finalized.",
                    tone = NowNoticeTone.NEUTRAL,
                )
            }

            ReceiptStage.READY -> {
                val receipt = uiState.receipt
                if (receipt == null) {
                    NowNotice(
                        title = "Receipt unavailable",
                        body = "Final receipt data is missing.",
                        tone = NowNoticeTone.ERROR,
                    )
                } else {
                    FinalReceiptHero(receipt)
                    ReceiptDetails(receipt)
                    NowPrimaryButton(
                        text = "Done",
                        onClick = onDone,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            ReceiptStage.ATTENTION -> {
                NowNotice(
                    title = "Receipt needs attention",
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
private fun ReceiptTopBar(
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
            text = "Final receipt",
            style = NowType.TitleM,
            color = NowColors.Ink950,
        )
    }
}

@Composable
private fun FinalizingReceiptCard(
    message: String,
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
            verticalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                color = NowColors.Blue600,
                trackColor = NowColors.Blue100,
                strokeWidth = 4.dp,
            )
            Text(
                text = "Finalizing receipt",
                style = NowType.TitleL,
                color = NowColors.Ink950,
            )
            Text(
                text = message,
                style = NowType.BodyM,
                color = NowColors.Ink600,
            )
        }
    }
}

@Composable
private fun FinalReceiptHero(
    receipt: FinalReceipt,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("receipt-final"),
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
                        imageVector = Icons.Outlined.ReceiptLong,
                        contentDescription = null,
                        tint = NowColors.LiveText,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }

            NowStatusChip(
                label = "FINAL",
                tone = NowStatusTone.LIVE,
                accessibilityLabel = "Final receipt",
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(NowSpacing.Space1),
            ) {
                Text(
                    text = displayJson(receipt.finalValue),
                    style = NowType.DataHero,
                    color = NowColors.Ink950,
                )
                Text(
                    text = "Verified result · " + humanize(receipt.verificationClass),
                    style = NowType.BodyM,
                    color = NowColors.Ink700,
                )
                Text(
                    text = "Observed · " + readableTime(receipt.observedAt),
                    style = NowType.BodyS,
                    color = NowColors.Ink500,
                )
            }
        }
    }
}

@Composable
private fun ReceiptDetails(
    receipt: FinalReceipt,
) {
    ReceiptSection(
        icon = Icons.Outlined.CheckCircle,
        title = "Reward",
    ) {
        ReceiptRow(
            label = "Amount",
            value = receipt.rewardAmountAtomic + " atomic units",
        )
        ReceiptRow(
            label = "Mint",
            value = shorten(receipt.rewardMint),
        )
    }

    ReceiptSection(
        icon = Icons.Outlined.Verified,
        title = "Settlement proof",
    ) {
        ReceiptRow(
            label = "Finalized",
            value = readableTime(receipt.finalizedAt),
        )
        ReceiptRow(
            label = "Signature",
            value = shorten(receipt.settlementSignature),
        )
        ReceiptRow(
            label = "Operation hash",
            value = shorten(receipt.settlementOperationHash),
        )
    }

    ReceiptSection(
        icon = Icons.Outlined.ReceiptLong,
        title = "Receipt integrity",
    ) {
        ReceiptRow(
            label = "Receipt ID",
            value = shorten(receipt.receiptId),
        )
        ReceiptRow(
            label = "Receipt digest",
            value = shorten(receipt.receiptDigest),
        )
        ReceiptRow(
            label = "Verification digest",
            value = shorten(receipt.verificationDigest),
        )
        ReceiptRow(
            label = "Revision",
            value = receipt.revision.toString(),
        )
    }
}

@Composable
private fun ReceiptSection(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    content: @Composable () -> Unit,
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
                    imageVector = icon,
                    contentDescription = null,
                    tint = NowColors.Blue600,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = title,
                    style = NowType.TitleS,
                    color = NowColors.Ink950,
                )
            }
            HorizontalDivider(color = NowColors.BorderSubtle)
            content()
        }
    }
}

@Composable
private fun ReceiptRow(
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
    if (value.length <= 24) value else value.take(10) + "…" + value.takeLast(10)

private fun humanize(value: String): String =
    value.lowercase()
        .replace('_', ' ')
        .replaceFirstChar { character ->
            if (character.isLowerCase()) character.titlecase() else character.toString()
        }

private fun readableTime(value: String): String =
    value.replace("T", " ").removeSuffix("Z") + " UTC"

private fun displayJson(value: kotlinx.serialization.json.JsonElement): String =
    when (value) {
        JsonNull -> "—"
        is JsonPrimitive -> value.contentOrNull ?: value.toString()
        else -> value.toString()
    }
