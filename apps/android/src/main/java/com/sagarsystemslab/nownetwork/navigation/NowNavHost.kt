package com.sagarsystemslab.nownetwork.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.sagarsystemslab.nownetwork.feature.activity.ActivityScreen
import com.sagarsystemslab.nownetwork.feature.activity.ActivityViewModel
import com.sagarsystemslab.nownetwork.feature.earn.EarnScreen
import com.sagarsystemslab.nownetwork.feature.earn.EarnViewModel
import com.sagarsystemslab.nownetwork.feature.home.NowScreen
import com.sagarsystemslab.nownetwork.feature.state.BrowseViewModel
import com.sagarsystemslab.nownetwork.feature.state.StateDetailScreen

@Composable
fun NowNavHost(
    appState: NowAppState,
    browseViewModel: BrowseViewModel,
    earnViewModel: EarnViewModel,
    activityViewModel: ActivityViewModel,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = appState.navController,
        startDestination = NowRoute,
        modifier = modifier,
    ) {
        composable<NowRoute> {
            val uiState by browseViewModel.homeState.collectAsStateWithLifecycle()

            NowScreen(
                uiState = uiState,
                serverNowMillis = browseViewModel::serverNowMillis,
                onRefresh = browseViewModel::refreshHome,
                onStateClick = appState::navigateToState,
            )
        }

        composable<StateDetailRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<StateDetailRoute>()
            val uiState by browseViewModel.detailState.collectAsStateWithLifecycle()

            LaunchedEffect(route.stateId) {
                browseViewModel.openState(route.stateId)
            }

            StateDetailScreen(
                uiState = uiState,
                serverNowMillis = browseViewModel::serverNowMillis,
                onBack = appState::navigateBack,
                onRetry = browseViewModel::retryState,
            )
        }

        composable<EarnRoute> {
            val uiState by earnViewModel.state.collectAsStateWithLifecycle()

            EarnScreen(
                uiState = uiState,
                rewardText = earnViewModel::rewardText,
                serverNowMillis = earnViewModel::serverNowMillis,
                onRefresh = earnViewModel::refresh,
            )
        }

        composable<ActivityRoute> {
            val uiState by activityViewModel.state.collectAsStateWithLifecycle()

            ActivityScreen(
                uiState = uiState,
                serverNowMillis = activityViewModel::serverNowMillis,
            )
        }
    }
}
