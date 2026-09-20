package com.sagarsystemslab.nownetwork

import android.test.InstrumentationTestCase

@Suppress("DEPRECATION")
class Phase0InstrumentedHarnessTest : InstrumentationTestCase() {
    fun testTargetPackageIdentity() {
        assertEquals(
            "com.sagarsystemslab.nownetwork",
            instrumentation.targetContext.packageName,
        )
    }
}
