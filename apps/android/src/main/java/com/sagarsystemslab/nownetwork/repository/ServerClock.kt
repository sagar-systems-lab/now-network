package com.sagarsystemslab.nownetwork.repository

import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerClock @Inject constructor() {
    private val offsetMillis = AtomicLong(0L)

    fun update(
        serverTimeIso: String,
        observedAtMillis: Long = System.currentTimeMillis(),
    ) {
        val serverMillis = Instant.parse(serverTimeIso).toEpochMilli()
        offsetMillis.set(serverMillis - observedAtMillis)
    }

    fun nowMillis(localNowMillis: Long = System.currentTimeMillis()): Long =
        localNowMillis + offsetMillis.get()

    fun offsetMillis(): Long = offsetMillis.get()
}
