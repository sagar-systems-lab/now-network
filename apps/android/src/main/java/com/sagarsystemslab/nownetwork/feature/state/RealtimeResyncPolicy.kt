package com.sagarsystemslab.nownetwork.feature.state

internal class RealtimeResyncPolicy {
    private var connectedOnce = false
    private var disconnectedAfterConnect = false

    fun onConnected(): Boolean {
        val shouldResync = connectedOnce && disconnectedAfterConnect
        disconnectedAfterConnect = false
        connectedOnce = true
        return shouldResync
    }

    fun onDisconnected() {
        if (connectedOnce) {
            disconnectedAfterConnect = true
        }
    }
}
