package com.sagarsystemslab.nownetwork.feature.common

import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout
import com.sagarsystemslab.nownetwork.model.GeoCenter

/** Claim the full gesture before a Compose scrolling parent can intercept a move. */
internal class MapGestureFrame(context: Context) : FrameLayout(context) {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        val handled = super.dispatchTouchEvent(event)
        val finished = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL
        parent?.requestDisallowInterceptTouchEvent(handled && !finished)
        return handled
    }
}

internal fun browseCamera(center: GeoCenter?, saved: DoubleArray?): DoubleArray {
    // Saveable state can be restored after the browse area changed on another screen.
    val matches = saved != null && saved.size == 5 &&
        saved[0].toBits() == (center?.latitude ?: Double.NaN).toBits() &&
        saved[1].toBits() == (center?.longitude ?: Double.NaN).toBits()
    return if (matches && saved != null && saved.drop(2).all { it.isFinite() }) {
        doubleArrayOf(saved[2].coerceIn(-85.0, 85.0), saved[3].coerceIn(-180.0, 180.0), saved[4].coerceIn(1.0, 19.0))
    } else {
        doubleArrayOf(center?.latitude?.coerceIn(-85.0, 85.0) ?: 20.0, center?.longitude ?: 0.0, if (center == null) 1.8 else 13.4)
    }
}

internal fun saveBrowseCamera(center: GeoCenter?, latitude: Double, longitude: Double, zoom: Double) =
    doubleArrayOf(center?.latitude ?: Double.NaN, center?.longitude ?: Double.NaN, latitude, longitude, zoom)
