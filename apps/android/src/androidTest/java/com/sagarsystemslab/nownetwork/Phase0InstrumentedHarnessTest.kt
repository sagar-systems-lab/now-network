package com.sagarsystemslab.nownetwork

import junit.framework.TestCase

class Phase0InstrumentedHarnessTest : TestCase() {
    fun testFrozenCoreContractIsReachableFromInstrumentedSourceSet() {
        assertEquals(
            "ASK / REFRESH → PROVE → KNOW → PAY",
            NowContract.CORE_LOOP,
        )
    }
}
