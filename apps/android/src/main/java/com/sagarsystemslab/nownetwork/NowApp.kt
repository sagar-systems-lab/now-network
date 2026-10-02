package com.sagarsystemslab.nownetwork

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.sagarsystemslab.nownetwork.designsystem.NowColors
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
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
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
                NowBottomBar(
                    destinations = TopLevelDestination.entries,
                    isSelected = currentDestination::isTopLevel,
                    onDestinationSelected = appState::navigateTo,
                )
            }
        },
    ) { innerPadding ->
        NowNavHost(
            appState = appState,
            darkTheme = darkTheme,
            onDarkThemeChange = onDarkThemeChange,
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
private fun NowBottomBar(
    destinations: List<TopLevelDestination>,
    isSelected: (TopLevelDestination) -> Boolean,
    onDestinationSelected: (TopLevelDestination) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        HorizontalDivider(
            thickness = 1.dp,
            color = NowColors.BorderSubtle,
        )
        NavigationBar(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .selectableGroup(),
            containerColor = NowColors.SurfacePrimary,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            windowInsets = WindowInsets.navigationBars,
        ) {
            destinations.forEach { destination ->
                val selected = isSelected(destination)
                NowNavigationItem(
                    destination = destination,
                    selected = selected,
                    onClick = {
                        if (!selected) {
                            onDestinationSelected(destination)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun RowScope.NowNavigationItem(
    destination: TopLevelDestination,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val foreground = if (selected) NowColors.Blue600 else NowColors.Ink500
    val iconBackground = when {
        selected -> NowColors.Blue50
        pressed -> NowColors.Ink100
        else -> NowColors.SurfacePrimary
    }

    Column(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 64.dp)
            .testTag(destination.testTag)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = interactionSource,
                indication = null,
            )
            .padding(top = 6.dp, bottom = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(
            space = 2.dp,
            alignment = Alignment.CenterVertically,
        ),
    ) {
        Box(
            modifier = Modifier
                .size(width = 34.dp, height = 28.dp)
                .background(
                    color = iconBackground,
                    shape = RoundedCornerShape(8.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = destination.icon,
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.size(21.dp),
            )
        }
        Text(
            text = destination.label,
            style = MaterialTheme.typography.labelMedium,
            color = foreground,
            maxLines = 1,
        )
    }
}
