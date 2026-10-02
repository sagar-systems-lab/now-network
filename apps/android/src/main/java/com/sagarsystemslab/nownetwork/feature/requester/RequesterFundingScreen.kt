package com.sagarsystemslab.nownetwork.feature.requester

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sagarsystemslab.nownetwork.designsystem.NowColors
import com.sagarsystemslab.nownetwork.designsystem.NowNotice
import com.sagarsystemslab.nownetwork.designsystem.NowNoticeTone
import com.sagarsystemslab.nownetwork.designsystem.NowPrimaryButton
import com.sagarsystemslab.nownetwork.designsystem.NowSecondaryButton
import com.sagarsystemslab.nownetwork.designsystem.NowSpacing
import com.sagarsystemslab.nownetwork.designsystem.NowTextField
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
            bottom = NowSpacing.Space8,
        ),
        verticalArrangement = Arrangement.spacedBy(NowSpacing.Space4),
    ) {
        item {
            RequesterTopBar(onBack = onBack)
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
                    text = "Pay for fresh proof of what is true right now.",
                    style = NowType.BodyM,
                    color = NowColors.Ink600,
                )
            }
        }

        item {
            FundingProgress(stage = uiState.stage)
        }

        uiState.notice?.let { notice ->
            item {
                NowNotice(
                    body = notice,
                    tone = when (uiState.stage) {
                        RequesterFundingStage.CONFIRMING -> NowNoticeTone.INFO
                        else -> NowNoticeTone.NEUTRAL
                    },
                )
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
                                    RequesterFundingStage.LOADING -> "Loading refresh request"
                                    RequesterFundingStage.PREPARING -> "Preparing refresh request"
                                    else -> "Waiting for wallet"
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
                    ProcessCard(
                        icon = Icons.Outlined.Refresh,
                        title = "Checking this state",
                        body = "Looking for an unfinished refresh before creating anything new.",
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
                    ProcessCard(
                        icon = Icons.Outlined.AccountBalanceWallet,
                        title = "Preparing refresh",
                        body = "Connecting your wallet and building the exact funding transaction. Nothing is sent yet.",
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
                    ProcessCard(
                        icon = Icons.Outlined.AccountBalanceWallet,
                        title = "Waiting for wallet",
                        body = "Review and approve the transaction in your wallet. NOW never receives your private key.",
                    )
                }
            }

            RequesterFundingStage.CONFIRMING -> {
                item {
                    NowNotice(
                        title = "Checking previous transaction",
                        body = "Do not submit again. NOW is reconciling the funding operation already sent from your wallet.",
                        tone = NowNoticeTone.INFO,
                    )
                }
                item {
                    ProcessCard(
                        icon = Icons.Outlined.Refresh,
                        title = "Confirming funding",
                        body = "The existing transaction is being checked against the authoritative backend before this refresh becomes available.",
                    )
                }
                item {
                    NowSecondaryButton(
                        text = "Check existing funding",
                        onClick = onCheck,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            RequesterFundingStage.COMPLETE -> {
                item {
                    FundingCompleteCard(uiState = uiState)
                }
            }
        }
    }
}

@Composable
private fun RequesterTopBar(
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
            text = "Refresh state",
            style = NowType.TitleM,
            color = NowColors.Ink950,
        )
    }
}

@Composable
private fun FundingProgress(
    stage: RequesterFundingStage,
) {
    val activeStep = when (stage) {
        RequesterFundingStage.LOADING -> 0
        RequesterFundingStage.SETUP,
        RequesterFundingStage.PREPARING -> 1
        RequesterFundingStage.REVIEW -> 2
        RequesterFundingStage.SUBMITTING,
        RequesterFundingStage.CONFIRMING,
        RequesterFundingStage.COMPLETE -> 3
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = NowColors.SurfacePrimary,
        border = BorderStroke(1.dp, NowColors.BorderSubtle),
    ) {
        Row(
            modifier = Modifier.padding(NowSpacing.Space3),
            horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FundingStep(
                number = 1,
                label = "Reward",
                active = activeStep >= 1,
                modifier = Modifier.weight(1f),
            )
            FundingStep(
                number = 2,
                label = "Review",
                active = activeStep >= 2,
                modifier = Modifier.weight(1f),
            )
            FundingStep(
                number = 3,
                label = "Fund",
                active = activeStep >= 3,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun FundingStep(
    number: Int,
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(24.dp),
            shape = CircleShape,
            color = if (active) NowColors.Blue50 else NowColors.Ink100,
            border = BorderStroke(
                1.dp,
                if (active) NowColors.Blue100 else NowColors.BorderSubtle,
            ),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = number.toString(),
                    style = NowType.LabelM,
                    color = if (active) NowColors.Blue700 else NowColors.Ink500,
                )
            }
        }
        Text(
            text = label,
            style = NowType.LabelM,
            color = if (active) NowColors.Ink800 else NowColors.Ink500,
        )
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
            ) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = NowColors.Blue50,
                    border = BorderStroke(1.dp, NowColors.Blue100),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Payments,
                            contentDescription = null,
                            tint = NowColors.Blue600,
                            modifier = Modifier.size(21.dp),
                        )
                    }
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "Set contributor reward",
                        style = NowType.TitleS,
                        color = NowColors.Ink950,
                    )
                    Text(
                        text = "This is what a verified contributor can earn.",
                        style = NowType.BodyS,
                        color = NowColors.Ink500,
                    )
                }
            }

            NowTextField(
                value = uiState.amountInput,
                onValueChange = onAmountChange,
                label = if (uiState.rewardSymbol.isBlank()) {
                    "Reward amount"
                } else {
                    "Reward amount (${uiState.rewardSymbol})"
                },
                supportingText = uiState.amountError ?: "Enter the reward for one verified refresh.",
                enabled = uiState.rewardConfigured,
                isError = uiState.amountError != null,
                modifier = Modifier.testTag("funding-amount"),
            )

            HorizontalDivider(color = NowColors.BorderSubtle)

            ReviewRow(
                label = "Network",
                value = uiState.network.ifBlank { "Not configured" },
            )

            if (!uiState.rewardConfigured) {
                NowNotice(
                    body = "Reward token configuration is required before a wallet transaction can be built.",
                    tone = NowNoticeTone.WARNING,
                )
            }

            NowPrimaryButton(
                text = "Review refresh",
                onClick = onPrepare,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("review-refresh-funding"),
                enabled = uiState.rewardConfigured,
            )
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
            Column(
                verticalArrangement = Arrangement.spacedBy(NowSpacing.Space1),
            ) {
                Text(
                    text = "Review refresh",
                    style = NowType.TitleM,
                    color = NowColors.Ink950,
                )
                Text(
                    text = "Confirm the reward and network before opening your wallet.",
                    style = NowType.BodyM,
                    color = NowColors.Ink600,
                )
            }

            HorizontalDivider(color = NowColors.BorderSubtle)

            ReviewRow(
                label = "Reward",
                value = listOf(uiState.amountInput, uiState.rewardSymbol)
                    .filter(String::isNotBlank)
                    .joinToString(" "),
            )
            ReviewRow(
                label = "Network",
                value = uiState.network.ifBlank { "Not configured" },
            )
            uiState.walletAddress?.let { wallet ->
                ReviewRow(
                    label = "Wallet",
                    value = shortWallet(wallet),
                )
            }

            NowNotice(
                body = "Your wallet signs and sends the transaction. NOW never receives your private key.",
                tone = NowNoticeTone.NEUTRAL,
            )

            NowPrimaryButton(
                text = "Fund refresh",
                onClick = onSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("fund-refresh"),
            )
        }
    }
}

@Composable
private fun FundingCompleteCard(
    uiState: RequesterFundingUiState,
) {
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
                    text = "Refresh funded",
                    style = NowType.TitleL,
                    color = NowColors.LiveText,
                )
                Text(
                    text = "Funding is confirmed. This refresh can now become available to nearby contributors.",
                    style = NowType.BodyM,
                    color = NowColors.Ink700,
                )
            }

            uiState.refreshId?.let { refreshId ->
                Text(
                    text = "Refresh · ${shortId(refreshId)}",
                    style = NowType.BodyS,
                    color = NowColors.Ink500,
                )
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
private fun ProcessCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
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
            Surface(
                modifier = Modifier.size(40.dp),
                shape = MaterialTheme.shapes.medium,
                color = NowColors.Blue50,
                border = BorderStroke(1.dp, NowColors.Blue100),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = NowColors.Blue600,
                        modifier = Modifier.size(21.dp),
                    )
                }
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

private fun shortWallet(value: String): String =
    if (value.length <= 12) value else "${value.take(6)}…${value.takeLast(4)}"

private fun shortId(value: String): String =
    if (value.length <= 12) value else "${value.take(8)}…"
