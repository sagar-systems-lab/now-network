package com.sagarsystemslab.nownetwork.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMacrobenchmarkApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMacrobenchmarkApi::class)
class PhysicalTopLevelNavigationBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun topLevelNavigationFramesUnmanagedCompilation() {
        rule.measureRepeated(
            packageName = TargetPackage,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Ignore(),
            iterations = 10,
            setupBlock = {
                startActivityAndWait()
                awaitText("NOW")
            },
        ) {
            openTopLevel(label = "EARN", expectedText = "Earn nearby")
            openTopLevel(label = "ACTIVITY", expectedText = "Your activity")
            openTopLevel(label = "NOW", expectedText = "What’s true around you right now?")
        }
    }
}
