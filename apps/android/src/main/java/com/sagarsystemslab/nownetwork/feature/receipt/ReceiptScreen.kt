package com.sagarsystemslab.nownetwork.feature.receipt

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
import com.sagarsystemslab.nownetwork.repository.FinalReceipt

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
                vertical = NowSpacing.Space3,
            )
            .testTag("screen-receipt"),
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
                text = "Final receipt",
                style = NowType.TitleL,
                color = NowColors.Ink950,
            )
        }

        when (uiState.stage) {
            ReceiptStage.FINALIZING -> {
                ReceiptCard(
                    title = "Finalizing receipt",
                    body = uiState.message,
                    progress = true,
                )
                Text(
                    text = "The receipt is generated from finalized settlement authority. No payment action is required.",
                    style = NowType.BodyS,
                    color = NowColors.Ink500,
                )
            }

            ReceiptStage.READY -> {
                val receipt = uiState.receipt
                if (receipt == null) {
                    ReceiptCard(
                        title = "Receipt unavailable",
                        body = "Final receipt data is missing.",
                    )
                } else {
                    ReceiptCard(
                        title = "Final",
                        body = "Payment proof is finalized and bound to the verified result.",
                    )
                    ReceiptDetails(receipt)
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onDone,
                    ) {
                        Text("Done")
                    }
                }
            }

            ReceiptStage.ATTENTION -> {
                ReceiptCard(
                    title = "Receipt needs attention",
                    body = uiState.message,
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
private fun ReceiptDetails(receipt: FinalReceipt) {
    Column(
        verticalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
    ) {
        ReceiptCard(
            title = "Verified result",
            body = buildString {
                append(receipt.finalValue.toString())
                append("\nObserved: ")
                append(receipt.observedAt)
                append("\nVerification: ")
                append(receipt.verificationClass)
            },
        )

        ReceiptCard(
            title = "Reward",
            body = buildString {
                append(receipt.rewardAmountAtomic)
                append(" atomic units")
                append("\nMint: ")
                append(shorten(receipt.rewardMint))
            },
        )

        ReceiptCard(
            title = "Settlement proof",
            body = buildString {
                append("Finalized: ")
                append(receipt.finalizedAt)
                append("\nSignature: ")
                append(shorten(receipt.settlementSignature))
                append("\nOperation hash: ")
                append(shorten(receipt.settlementOperationHash))
            },
        )

        ReceiptCard(
            title = "Receipt integrity",
            body = buildString {
                append("Receipt ID: ")
                append(shorten(receipt.receiptId))
                append("\nDigest: ")
                append(shorten(receipt.receiptDigest))
                append("\nVerification digest: ")
                append(shorten(receipt.verificationDigest))
                append("\nRevision: ")
                append(receipt.revision)
            },
        )
    }
}

@Composable
private fun ReceiptCard(
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

private fun shorten(value: String): String =
    if (value.length <= 24) value else "${value.take(10)}…${value.takeLast(10)}"
