package com.sagarsystemslab.nownetwork.feature.common

import com.sagarsystemslab.nownetwork.model.GeoCenter
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class BrowseCameraTest {
    @Test fun returningFromLocationPickerDiscardsPreviousAreaCamera() {
        val previous = GeoCenter(0.0, 0.0)
        val location = GeoCenter(28.61, 77.21)
        val saved = saveBrowseCamera(previous, -10.0, -20.0, 2.0)
        assertArrayEquals(doubleArrayOf(28.61, 77.21, 13.4), browseCamera(location, saved), 0.0001)
    }

    @Test fun tabReturnKeepsPanAndZoomWithinTheSameArea() {
        val area = GeoCenter(28.61, 77.21)
        val saved = saveBrowseCamera(area, 28.63, 77.23, 16.2)
        assertArrayEquals(doubleArrayOf(28.63, 77.23, 16.2), browseCamera(area, saved), 0.0001)
    }

    @Test fun firstLocationReplacesUnselectedWorldView() {
        val saved = saveBrowseCamera(null, 0.0, 0.0, 1.8)
        assertArrayEquals(doubleArrayOf(-33.87, 151.21, 13.4), browseCamera(GeoCenter(-33.87, 151.21), saved), 0.0001)
    }

    @Test fun invalidSavedCameraFallsBackToSelectedArea() {
        val area = GeoCenter(28.61, 77.21)
        val saved = saveBrowseCamera(area, Double.NaN, 77.23, 16.2)
        assertArrayEquals(doubleArrayOf(28.61, 77.21, 13.4), browseCamera(area, saved), 0.0001)
    }
}
