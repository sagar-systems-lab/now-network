package com.sagarsystemslab.nownetwork.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMacrobenchmarkApi
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMacrobenchmarkApi::class)
class PhysicalDeviceStartupBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun coldUnmanagedCompilation() = startup(StartupMode.COLD)

    @Test
    fun warmUnmanagedCompilation() = startup(StartupMode.WARM)

    @Test
    fun hotUnmanagedCompilation() = startup(StartupMode.HOT)

    private fun startup(startupMode: StartupMode) {
        rule.measureRepeated(
            packageName = TargetPackage,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.Ignore(),
            startupMode = startupMode,
            iterations = 10,
            setupBlock = {
                pressHome()
            },
        ) {
            startActivityAndWait()
        }
    }
}
