package com.sagarsystemslab.nownetwork.config

data class BrowseAreaConfig(
    val label: String,
    val latitude: Double?,
    val longitude: Double?,
    val radiusMeters: Int,
) {
    val configured: Boolean
        get() =
            label.isNotBlank() &&
                latitude != null &&
                latitude.isFinite() &&
                latitude in -90.0..90.0 &&
                longitude != null &&
                longitude.isFinite() &&
                longitude in -180.0..180.0 &&
                radiusMeters in 1..50_000
}
