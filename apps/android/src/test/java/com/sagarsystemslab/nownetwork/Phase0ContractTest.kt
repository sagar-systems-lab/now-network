package com.sagarsystemslab.nownetwork

import org.junit.Assert.assertEquals
import org.junit.Test

class Phase0ContractTest {
    @Test
    fun coreLoopMatchesFrozenProductContract() {
        assertEquals(
            "ASK / REFRESH → PROVE → KNOW → PAY",
            NowContract.CORE_LOOP,
        )
    }
}
