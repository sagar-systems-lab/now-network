package com.sagarsystemslab.nownetwork.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

internal const val TargetPackage = "com.sagarsystemslab.nownetwork"

internal fun MacrobenchmarkScope.awaitText(text: String) {
    check(device.wait(Until.hasObject(By.text(text)), 5_000)) {
        "Timed out waiting for text: $text"
    }
}

internal fun MacrobenchmarkScope.openTopLevel(label: String, expectedText: String) {
    val destination = device.wait(Until.findObject(By.text(label)), 5_000)
        ?: error("Top-level destination not found: $label")
    destination.click()
    awaitText(expectedText)
}
