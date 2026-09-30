package com.sagarsystemslab.nownetwork.feature.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeResyncPolicyTest {
    @Test
    fun firstConnectionDoesNotForceSnapshot() {
        val policy = RealtimeResyncPolicy()

        assertFalse(policy.onConnected())
    }

    @Test
    fun disconnectBeforeFirstConnectionDoesNotForceSnapshot() {
        val policy = RealtimeResyncPolicy()

        policy.onDisconnected()

        assertFalse(policy.onConnected())
    }

    @Test
    fun reconnectAfterEstablishedDisconnectForcesExactlyOneSnapshot() {
        val policy = RealtimeResyncPolicy()

        assertFalse(policy.onConnected())
        policy.onDisconnected()

        assertTrue(policy.onConnected())
        assertFalse(policy.onConnected())
    }

    @Test
    fun repeatedDisconnectsCollapseIntoOneResync() {
        val policy = RealtimeResyncPolicy()

        assertFalse(policy.onConnected())
        policy.onDisconnected()
        policy.onDisconnected()

        assertTrue(policy.onConnected())
        assertFalse(policy.onConnected())
    }
}
