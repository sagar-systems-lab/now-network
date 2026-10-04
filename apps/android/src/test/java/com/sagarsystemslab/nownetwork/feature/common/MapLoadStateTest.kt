package com.sagarsystemslab.nownetwork.feature.common

import org.junit.Assert.*
import org.junit.Test

class MapLoadStateTest {
    @Test fun backgroundFrameWithoutMapTilesIsNotAReadyMap() {
        val backgroundOnly = MapLoadState().styleLoaded().rendered(true)
        assertFalse(backgroundOnly.ready)
        assertTrue(backgroundOnly.tileReceived().ready)
    }

    @Test fun tileFailureCannotBeClearedByRenderingAnEmptyFrame() {
        val ready = MapLoadState().styleLoaded().tileReceived().rendered(true)
        assertTrue(ready.ready)
        val missingDetails = ready.failure().rendered(true)
        assertFalse(missingDetails.ready)
        assertTrue(missingDetails.tileReceived().rendered(true).ready)
    }

    @Test fun receivedTilesStillNeedACompleteFrame() {
        val loading = MapLoadState().tileReceived().styleLoaded().rendered(false)
        assertFalse(loading.ready)
        assertTrue(loading.rendered(true).ready)
    }
}
