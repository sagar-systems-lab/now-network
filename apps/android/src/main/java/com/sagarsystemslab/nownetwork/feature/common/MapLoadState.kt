package com.sagarsystemslab.nownetwork.feature.common

internal data class MapLoadState(
    val styleLoaded: Boolean = false,
    val tileReceived: Boolean = false,
    val frameRendered: Boolean = false,
    val failed: Boolean = false,
) {
    val ready: Boolean get() = styleLoaded && tileReceived && frameRendered && !failed
    fun styleLoaded() = copy(styleLoaded = true)
    fun tileReceived() = copy(tileReceived = true, failed = false)
    fun rendered(fully: Boolean) = copy(frameRendered = fully)
    fun failure() = copy(failed = true, frameRendered = false)
}
