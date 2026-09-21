package com.sagarsystemslab.nownetwork

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoreLoopInstrumentedTest {
    @Test
    fun coreLoopContractIsAvailable() {
        assertEquals(
            "ASK / REFRESH → PROVE → KNOW → PAY",
            NowContract.CORE_LOOP,
        )
    }
}
