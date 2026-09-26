package com.sagarsystemslab.nownetwork.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.sagarsystemslab.nownetwork.feature.activity.ActivityScreen
import com.sagarsystemslab.nownetwork.feature.earn.EarnScreen
import com.sagarsystemslab.nownetwork.feature.home.NowScreen

@Composable
fun NowNavHost(
    appState: NowAppState,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = appState.navController,
        startDestination = NowRoute,
        modifier = modifier,
    ) {
        composable<NowRoute> {
            NowScreen()
        }
        composable<EarnRoute> {
            EarnScreen()
        }
        composable<ActivityRoute> {
            ActivityScreen()
        }
    }
}
