package com.sagarsystemslab.nownetwork

import junit.framework.TestCase

class CoreLoopInstrumentedTest : TestCase() {
    fun testCoreLoopContractIsAvailable() {
        assertEquals(
            "ASK / REFRESH → PROVE → KNOW → PAY",
            NowContract.CORE_LOOP,
        )
    }
}
