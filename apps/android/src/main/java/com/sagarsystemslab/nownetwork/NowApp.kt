package com.sagarsystemslab.nownetwork

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.sagarsystemslab.nownetwork.designsystem.NowColors
import com.sagarsystemslab.nownetwork.feature.activity.ActivityViewModel
import com.sagarsystemslab.nownetwork.feature.capture.EvidenceCaptureViewModel
import com.sagarsystemslab.nownetwork.feature.earn.ContributorClaimViewModel
import com.sagarsystemslab.nownetwork.feature.earn.EarnViewModel
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
    browseViewModel: BrowseViewModel,
    earnViewModel: EarnViewModel,
    contributorClaimViewModel: ContributorClaimViewModel,
    evidenceCaptureViewModel: EvidenceCaptureViewModel,
    verificationViewModel: VerificationViewModel,
    activityViewModel: ActivityViewModel,
    requesterFundingViewModel: RequesterFundingViewModel,
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
                        containerColor = NowColors.SurfacePrimary,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        TopLevelDestination.entries.forEach { destination ->
                            val selected = currentDestination.isTopLevel(destination)
                            NavigationBarItem(
                                modifier = Modifier.testTag(destination.testTag),
                                selected = selected,
                                onClick = { appState.navigateTo(destination) },
                                icon = {
                                    Icon(
                                        imageVector = destination.icon,
                                        contentDescription = null,
                                    )
                                },
                                label = { Text(destination.label) },
                                alwaysShowLabel = true,
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = NowColors.Blue600,
                                    selectedTextColor = NowColors.Blue600,
                                    indicatorColor = NowColors.Blue50,
                                    unselectedIconColor = NowColors.Ink500,
                                    unselectedTextColor = NowColors.Ink500,
                                ),
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        NowNavHost(
            appState = appState,
            browseViewModel = browseViewModel,
            earnViewModel = earnViewModel,
            contributorClaimViewModel = contributorClaimViewModel,
            evidenceCaptureViewModel = evidenceCaptureViewModel,
            verificationViewModel = verificationViewModel,
            activityViewModel = activityViewModel,
            requesterFundingViewModel = requesterFundingViewModel,
            walletInteractionHost = walletInteractionHost,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
    }
}
