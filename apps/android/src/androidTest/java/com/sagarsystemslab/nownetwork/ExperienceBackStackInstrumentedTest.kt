package com.sagarsystemslab.nownetwork

import androidx.navigation.NavHostController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.toRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sagarsystemslab.nownetwork.experience.ExperienceDestination
import com.sagarsystemslab.nownetwork.experience.ExperienceRoute
import com.sagarsystemslab.nownetwork.navigation.NowAppState
import com.sagarsystemslab.nownetwork.navigation.NowRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExperienceBackStackInstrumentedTest {
    @Test fun nestedSettingsReturnToTheirActualParent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val controller = NavHostController(instrumentation.targetContext)
            controller.navigatorProvider.addNavigator(ComposeNavigator())
            controller.graph = controller.createGraph(startDestination = NowRoute) {
                composable<NowRoute> { }
                composable<ExperienceRoute> { }
            }
            val app = NowAppState(controller)
            app.navigateToExperience(ExperienceDestination.PROFILE)
            app.navigateToExperience(ExperienceDestination.SETTINGS)
            app.navigateToExperience(ExperienceDestination.APPEARANCE)
            app.navigateToExperience(ExperienceDestination.APPEARANCE)
            app.navigateBack()
            assertEquals(ExperienceDestination.SETTINGS, controller.currentBackStackEntry!!.toRoute<ExperienceRoute>().destination)
            app.navigateBack()
            assertEquals(ExperienceDestination.PROFILE, controller.currentBackStackEntry!!.toRoute<ExperienceRoute>().destination)
            app.navigateBack()
            assertTrue(controller.currentDestination!!.hasRoute<NowRoute>())
        }
    }
}
