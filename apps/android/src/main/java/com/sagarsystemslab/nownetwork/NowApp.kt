package com.sagarsystemslab.nownetwork

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.sagarsystemslab.nownetwork.designsystem.NowColors
import com.sagarsystemslab.nownetwork.designsystem.NowMotion
import com.sagarsystemslab.nownetwork.feature.activity.ActivityViewModel
import com.sagarsystemslab.nownetwork.feature.capture.EvidenceCaptureViewModel
import com.sagarsystemslab.nownetwork.feature.earn.ContributorClaimViewModel
import com.sagarsystemslab.nownetwork.feature.earn.EarnViewModel
import com.sagarsystemslab.nownetwork.feature.payment.PaymentViewModel
import com.sagarsystemslab.nownetwork.feature.receipt.ReceiptViewModel
import com.sagarsystemslab.nownetwork.feature.requester.RequesterFundingViewModel
import com.sagarsystemslab.nownetwork.feature.state.BrowseViewModel
import com.sagarsystemslab.nownetwork.feature.verification.VerificationViewModel
import com.sagarsystemslab.nownetwork.navigation.NowNavHost
import com.sagarsystemslab.nownetwork.navigation.TopLevelDestination
import com.sagarsystemslab.nownetwork.navigation.isTopLevel
import com.sagarsystemslab.nownetwork.navigation.rememberNowAppState
import com.sagarsystemslab.nownetwork.wallet.WalletInteractionHost

@Composable
fun NowApp(
    browseViewModelProvider: () -> BrowseViewModel,
    earnViewModelProvider: () -> EarnViewModel,
    contributorClaimViewModelProvider: () -> ContributorClaimViewModel,
    evidenceCaptureViewModelProvider: () -> EvidenceCaptureViewModel,
    verificationViewModelProvider: () -> VerificationViewModel,
    paymentViewModelProvider: () -> PaymentViewModel,
    receiptViewModelProvider: () -> ReceiptViewModel,
    activityViewModelProvider: () -> ActivityViewModel,
    requesterFundingViewModelProvider: () -> RequesterFundingViewModel,
    walletInteractionHost: WalletInteractionHost,
) {
    val appState = rememberNowAppState()
    val currentDestination = appState.currentDestination
    val showBottomBar = TopLevelDestination.entries.any { destination ->
        currentDestination.isTopLevel(destination)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = NowColors.SurfaceCanvas,
        contentColor = MaterialTheme.colorScheme.onBackground,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            if (showBottomBar) {
                Column {
                    HorizontalDivider(color = NowColors.BorderSubtle)
                    NavigationBar(
                        modifier = Modifier.selectableGroup(),
                        containerColor = NowColors.SurfacePrimary,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        TopLevelDestination.entries.forEach { destination ->
                            val selected = currentDestination.isTopLevel(destination)
                            LiveNavigationItem(
                                destination = destination,
                                selected = selected,
                                onClick = { appState.navigateTo(destination) },
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        NowNavHost(
            appState = appState,
            browseViewModelProvider = browseViewModelProvider,
            earnViewModelProvider = earnViewModelProvider,
            contributorClaimViewModelProvider = contributorClaimViewModelProvider,
            evidenceCaptureViewModelProvider = evidenceCaptureViewModelProvider,
            verificationViewModelProvider = verificationViewModelProvider,
            paymentViewModelProvider = paymentViewModelProvider,
            receiptViewModelProvider = receiptViewModelProvider,
            activityViewModelProvider = activityViewModelProvider,
            requesterFundingViewModelProvider = requesterFundingViewModelProvider,
            walletInteractionHost = walletInteractionHost,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
    }
}

@Composable
private fun RowScope.LiveNavigationItem(
    destination: TopLevelDestination,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val foreground by animateColorAsState(
        targetValue = if (selected) NowColors.Blue700 else NowColors.Ink500,
        animationSpec = tween(NowMotion.FastMillis),
        label = "nav-foreground",
    )
    val indicator by animateColorAsState(
        targetValue = if (selected) NowColors.Blue50 else NowColors.SurfacePrimary,
        animationSpec = tween(NowMotion.FastMillis),
        label = "nav-indicator",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.06f else 1f,
        animationSpec = tween(NowMotion.FastMillis),
        label = "nav-icon-scale",
    )

    Column(
        modifier = Modifier
            .weight(1f)
            .height(64.dp)
            .testTag(destination.testTag)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = interactionSource,
                indication = null,
            )
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .background(
                    color = indicator,
                    shape = MaterialTheme.shapes.extraLarge,
                )
                .padding(horizontal = 12.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = destination.icon,
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.scale(iconScale),
            )
        }
        Text(
            text = destination.label,
            style = MaterialTheme.typography.labelMedium,
            color = foreground,
        )
    }
}
