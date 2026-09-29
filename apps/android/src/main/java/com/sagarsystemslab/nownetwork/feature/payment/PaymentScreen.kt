package com.sagarsystemslab.nownetwork.feature.payment

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

@Composable
fun PaymentScreen(
    uiState: PaymentUiState,
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
            .testTag("screen-payment"),
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
                text = "Payment",
                style = NowType.TitleL,
                color = NowColors.Ink950,
            )
        }

        when (uiState.stage) {
            PaymentStage.PENDING -> {
                PaymentStatusCard(
                    title = "Payment pending",
                    body = uiState.message,
                    progress = true,
                )
            }

            PaymentStage.VERIFYING -> {
                PaymentStatusCard(
                    title = "Verifying payment",
                    body = uiState.message,
                    progress = true,
                )
            }

            PaymentStage.PAID -> {
                PaymentStatusCard(
                    title = "Paid",
                    body = uiState.message,
                )
                PaymentAuthorityCard(uiState)
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDone,
                ) {
                    Text("View activity")
                }
            }

            PaymentStage.FAILED -> {
                PaymentStatusCard(
                    title = "Payment needs support",
                    body = uiState.message,
                )
                PaymentAuthorityCard(uiState)
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDone,
                ) {
                    Text("View activity")
                }
            }

            PaymentStage.ATTENTION -> {
                PaymentStatusCard(
                    title = "Payment status unavailable",
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

        if (
            uiState.stage == PaymentStage.PENDING ||
            uiState.stage == PaymentStage.VERIFYING
        ) {
            Text(
                text = "You can leave this screen. Payment reconciliation continues independently.",
                style = NowType.BodyS,
                color = NowColors.Ink500,
            )
        }

        Spacer(Modifier.height(NowSpacing.Space3))
    }
}

@Composable
private fun PaymentAuthorityCard(uiState: PaymentUiState) {
    PaymentStatusCard(
        title = "Settlement",
        body = buildString {
            append(uiState.settlementStatus.lowercase().replace('_', ' '))
            uiState.chainCommitment?.let {
                append(" · ")
                append(it)
            }
            uiState.chainSignature?.let {
                append("\nSignature: ")
                append(shorten(it))
            }
        },
    )
}

@Composable
private fun PaymentStatusCard(
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
    if (value.length <= 18) value else "${value.take(8)}…${value.takeLast(8)}"
