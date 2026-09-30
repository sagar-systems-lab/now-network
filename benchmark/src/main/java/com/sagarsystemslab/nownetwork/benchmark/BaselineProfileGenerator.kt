package com.sagarsystemslab.nownetwork.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() {
        rule.collect(
            packageName = TargetPackage,
            includeInStartupProfile = true,
        ) {
            pressHome()
            startActivityAndWait()
            awaitText("NOW")
        }
    }

    @Test
    fun topLevelNavigation() {
        rule.collect(
            packageName = TargetPackage,
            includeInStartupProfile = false,
        ) {
            startActivityAndWait()
            awaitText("NOW")
            openTopLevel(label = "EARN", expectedText = "Earn nearby")
            openTopLevel(label = "ACTIVITY", expectedText = "Your activity")
            openTopLevel(label = "NOW", expectedText = "What’s true around you right now?")
        }
    }
}
