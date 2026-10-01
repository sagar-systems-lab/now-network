package com.sagarsystemslab.nownetwork.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun coldNoCompilation() = startup(
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD,
    )

    @Test
    fun coldBaselineProfile() = startup(
        compilationMode = CompilationMode.Partial(
            baselineProfileMode = BaselineProfileMode.Require,
        ),
        startupMode = StartupMode.COLD,
    )

    @Test
    fun warmBaselineProfile() = startup(
        compilationMode = CompilationMode.Partial(
            baselineProfileMode = BaselineProfileMode.Require,
        ),
        startupMode = StartupMode.WARM,
    )

    @Test
    fun hotBaselineProfile() = startup(
        compilationMode = CompilationMode.Partial(
            baselineProfileMode = BaselineProfileMode.Require,
        ),
        startupMode = StartupMode.HOT,
    )

    private fun startup(
        compilationMode: CompilationMode,
        startupMode: StartupMode,
    ) {
        rule.measureRepeated(
            packageName = TargetPackage,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = compilationMode,
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
