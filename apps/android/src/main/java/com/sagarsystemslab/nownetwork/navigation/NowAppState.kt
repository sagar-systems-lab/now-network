package com.sagarsystemslab.nownetwork.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination

enum class TopLevelDestination(
    val label: String,
    val icon: ImageVector,
    val testTag: String,
) {
    NOW(
        label = "NOW",
        icon = Icons.Outlined.Home,
        testTag = "nav-now",
    ),
    EARN(
        label = "EARN",
        icon = Icons.Outlined.WorkOutline,
        testTag = "nav-earn",
    ),
    ACTIVITY(
        label = "ACTIVITY",
        icon = Icons.Outlined.History,
        testTag = "nav-activity",
    ),
}

@Stable
class NowAppState(
    val navController: NavHostController,
) {
    val currentDestination: NavDestination?
        @Composable get() {
            val entry by navController.currentBackStackEntryAsState()
            return entry?.destination
        }

    fun navigateToState(stateId: String) {
        navController.navigate(StateDetailRoute(stateId = stateId))
    }

    fun navigateToRequesterFunding(stateId: String) {
        navController.navigate(RequesterFundingRoute(stateId = stateId))
    }

    fun navigateToOpportunity(refreshId: String) {
        navController.navigate(OpportunityRoute(refreshId = refreshId))
    }

    fun navigateToEvidence(
        acceptanceId: String,
        refreshId: String,
    ) {
        navController.navigate(
            EvidenceCaptureRoute(
                acceptanceId = acceptanceId,
                refreshId = refreshId,
            ),
        )
    }

    fun navigateToVerification(refreshId: String) {
        navController.navigate(VerificationRoute(refreshId = refreshId))
    }

    fun navigateToPayment(refreshId: String) {
        navController.navigate(PaymentRoute(refreshId = refreshId))
    }

    fun navigateToReceipt(refreshId: String) {
        navController.navigate(ReceiptRoute(refreshId = refreshId))
    }

    fun navigateBack() {
        navController.popBackStack()
    }

    fun navigateTo(destination: TopLevelDestination) {
        when (destination) {
            TopLevelDestination.NOW -> navController.navigate(NowRoute) {
                topLevelOptions(navController)
            }

            TopLevelDestination.EARN -> navController.navigate(EarnRoute) {
                topLevelOptions(navController)
            }

            TopLevelDestination.ACTIVITY -> navController.navigate(ActivityRoute) {
                topLevelOptions(navController)
            }
        }
    }
}

private fun NavOptionsBuilder.topLevelOptions(
    navController: NavHostController,
) {
    popUpTo(navController.graph.findStartDestination().id) {
        saveState = true
    }
    launchSingleTop = true
    restoreState = true
}

fun NavDestination?.isTopLevel(destination: TopLevelDestination): Boolean =
    when (destination) {
        TopLevelDestination.NOW -> this?.hasRoute<NowRoute>() == true
        TopLevelDestination.EARN -> this?.hasRoute<EarnRoute>() == true
        TopLevelDestination.ACTIVITY -> this?.hasRoute<ActivityRoute>() == true
    }

@Composable
fun rememberNowAppState(
    navController: NavHostController = rememberNavController(),
): NowAppState = remember(navController) {
    NowAppState(navController)
}
