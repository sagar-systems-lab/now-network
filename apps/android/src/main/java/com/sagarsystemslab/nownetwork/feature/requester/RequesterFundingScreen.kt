package com.sagarsystemslab.nownetwork.feature.requester

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sagarsystemslab.nownetwork.designsystem.NowColors
import com.sagarsystemslab.nownetwork.designsystem.NowSpacing
import com.sagarsystemslab.nownetwork.designsystem.NowType

@Composable
fun RequesterFundingScreen(
    uiState: RequesterFundingUiState,
    onBack: () -> Unit,
    onAmountChange: (String) -> Unit,
    onPrepare: () -> Unit,
    onSubmit: () -> Unit,
    onCheck: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.testTag("screen-requester-funding"),
        contentPadding = PaddingValues(
            start = NowSpacing.PageHorizontal,
            top = NowSpacing.Space2,
            end = NowSpacing.PageHorizontal,
            bottom = NowSpacing.Space6,
        ),
        verticalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
    ) {
        item {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = NowColors.Ink700,
                )
            }
        }

        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(NowSpacing.Space1),
            ) {
                Text(
                    text = uiState.title,
                    style = NowType.TitleXL,
                    color = NowColors.Ink950,
                )
                Text(
                    text = "Fund a fresh proof request.",
                    style = NowType.BodyM,
                    color = NowColors.Ink600,
                )
            }
        }

        uiState.notice?.let { notice ->
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = NowColors.InfoSoft,
                    border = BorderStroke(1.dp, NowColors.InfoBorder),
                ) {
                    Text(
                        modifier = Modifier.padding(NowSpacing.Space3),
                        text = notice,
                        style = NowType.BodyM,
                        color = NowColors.InfoText,
                    )
                }
            }
        }

        when (uiState.stage) {
            RequesterFundingStage.LOADING,
            RequesterFundingStage.PREPARING,
            RequesterFundingStage.SUBMITTING -> {
                item {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = when (uiState.stage) {
                                    RequesterFundingStage.LOADING -> "Loading refresh funding"
                                    RequesterFundingStage.PREPARING -> "Preparing refresh funding"
                                    else -> "Sending refresh funding"
                                }
                            },
                        color = NowColors.Blue600,
                        trackColor = NowColors.Blue100,
                    )
                }
            }

            else -> Unit
        }

        when (uiState.stage) {
            RequesterFundingStage.LOADING -> {
                item {
                    StatusCard(
                        title = "Loading current state",
                        body = "Checking for unfinished requester activity.",
                    )
                }
            }

            RequesterFundingStage.SETUP -> {
                item {
                    SetupCard(
                        uiState = uiState,
                        onAmountChange = onAmountChange,
                        onPrepare = onPrepare,
                    )
                }
            }

            RequesterFundingStage.PREPARING -> {
                item {
                    StatusCard(
                        title = "Preparing refresh",
                        body = "Connecting the wallet, binding control, and building the exact Solana transaction.",
                    )
                }
            }

            RequesterFundingStage.REVIEW -> {
                item {
                    ReviewCard(
                        uiState = uiState,
                        onSubmit = onSubmit,
                    )
                }
            }

            RequesterFundingStage.SUBMITTING -> {
                item {
                    StatusCard(
                        title = "Waiting for wallet",
                        body = "Approve the transaction in your wallet. NOW will not treat submission as confirmation.",
                    )
                }
            }

            RequesterFundingStage.CONFIRMING -> {
                item {
                    StatusCard(
                        title = "Confirming funding",
                        body = "The transaction will not be sent again. NOW is reconciling the existing onchain operation.",
                    )
                }
                item {
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onCheck,
                    ) {
                        Text("Check existing funding")
                    }
                }
            }

            RequesterFundingStage.COMPLETE -> {
                item {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("funding-confirmed"),
                        shape = MaterialTheme.shapes.large,
                        color = NowColors.LiveSoft,
                        border = BorderStroke(1.dp, NowColors.LiveBorder),
                    ) {
                        Column(
                            modifier = Modifier.padding(NowSpacing.Space4),
                            verticalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
                        ) {
                            Text(
                                text = "Refresh funded",
                                style = NowType.TitleM,
                                color = NowColors.LiveText,
                            )
                            Text(
                                text = "Funding is confirmed by the authoritative backend and the refresh can now become available to contributors.",
                                style = NowType.BodyM,
                                color = NowColors.Ink700,
                            )
                            uiState.refreshId?.let {
                                Text(
                                    text = "Refresh · ${shortId(it)}",
                                    style = NowType.BodyS,
                                    color = NowColors.Ink500,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupCard(
    uiState: RequesterFundingUiState,
    onAmountChange: (String) -> Unit,
    onPrepare: () -> Unit,
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
                text = "Set reward",
                style = NowType.TitleM,
                color = NowColors.Ink950,
            )

            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("funding-amount"),
                value = uiState.amountInput,
                onValueChange = onAmountChange,
                enabled = uiState.rewardConfigured,
                singleLine = true,
                label = {
                    Text(
                        if (uiState.rewardSymbol.isBlank()) {
                            "Reward amount"
                        } else {
                            "Reward amount (${uiState.rewardSymbol})"
                        },
                    )
                },
                isError = uiState.amountError != null,
                supportingText = uiState.amountError?.let { error ->
                    { Text(error) }
                },
            )

            Text(
                text = "Network · ${uiState.network.ifBlank { "not configured" }}",
                style = NowType.BodyS,
                color = NowColors.Ink500,
            )

            if (!uiState.rewardConfigured) {
                Text(
                    text = "Reward mint configuration is required before a wallet transaction can be built.",
                    style = NowType.BodyS,
                    color = NowColors.StaleText,
                )
            }

            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("review-refresh-funding"),
                onClick = onPrepare,
                enabled = uiState.rewardConfigured,
                colors = ButtonDefaults.buttonColors(
                    containerColor = NowColors.Blue600,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text("Review refresh")
            }
        }
    }
}

@Composable
private fun ReviewCard(
    uiState: RequesterFundingUiState,
    onSubmit: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("funding-review"),
        shape = MaterialTheme.shapes.large,
        color = NowColors.SurfacePrimary,
        border = BorderStroke(1.dp, NowColors.BorderSubtle),
    ) {
        Column(
            modifier = Modifier.padding(NowSpacing.Space4),
            verticalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
        ) {
            Text(
                text = "Review funding",
                style = NowType.TitleM,
                color = NowColors.Ink950,
            )

            ReviewRow(
                label = "Reward",
                value = listOf(uiState.amountInput, uiState.rewardSymbol)
                    .filter(String::isNotBlank)
                    .joinToString(" "),
            )
            ReviewRow(
                label = "Network",
                value = uiState.network,
            )
            uiState.walletAddress?.let { wallet ->
                ReviewRow(
                    label = "Wallet",
                    value = shortWallet(wallet),
                )
            }

            Text(
                text = "Your wallet signs and sends the transaction. NOW never receives your private key.",
                style = NowType.BodyS,
                color = NowColors.Ink500,
            )

            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("fund-refresh"),
                onClick = onSubmit,
                colors = ButtonDefaults.buttonColors(
                    containerColor = NowColors.Blue600,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text("Fund refresh")
            }
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
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = NowType.BodyM,
            color = NowColors.Ink500,
        )
        Text(
            text = value,
            style = NowType.LabelL,
            color = NowColors.Ink800,
        )
    }
}

@Composable
private fun StatusCard(
    title: String,
    body: String,
) {
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
                text = title,
                style = NowType.TitleM,
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

private fun shortWallet(value: String): String =
    if (value.length <= 12) value else "${value.take(6)}…${value.takeLast(4)}"

private fun shortId(value: String): String =
    if (value.length <= 12) value else "${value.take(8)}…"
