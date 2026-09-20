package com.sagarsystemslab.nownetwork

import org.junit.Assert.assertEquals
import org.junit.Test

class CoreLoopContractTest {
    @Test
    fun coreLoopMatchesContract() {
        assertEquals(
            "ASK / REFRESH → PROVE → KNOW → PAY",
            NowContract.CORE_LOOP,
        )
    }
}
