package com.sagarsystemslab.nownetwork.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerClockTest {
    @Test
    fun serverOffsetIsAppliedToLocalClock() {
        val clock = ServerClock()

        clock.update(
            serverTimeIso = "2026-09-26T12:00:05Z",
            observedAtMillis = 1_790_424_000_000L,
        )

        assertEquals(5_000L, clock.offsetMillis())
        assertEquals(1_790_424_006_000L, clock.nowMillis(1_790_424_001_000L))
    }
}
