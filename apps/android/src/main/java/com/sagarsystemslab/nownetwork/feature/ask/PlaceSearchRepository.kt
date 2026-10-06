package com.sagarsystemslab.nownetwork.feature.ask

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import com.sagarsystemslab.nownetwork.model.GeoCenter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

@Singleton
class PlaceSearchRepository @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun search(query: String): List<PlaceSuggestion> {
        if (!Geocoder.isPresent()) throw IOException("Address search isn't available on this device. Tap the map to choose a place.")
        val geocoder = Geocoder(context, Locale.getDefault())
        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine<List<Address>> { continuation ->
                geocoder.getFromLocationName(query, 6, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        if (continuation.isActive) continuation.resume(addresses)
                    }
                    override fun onError(errorMessage: String?) {
                        if (continuation.isActive) continuation.resumeWithException(IOException("Address search is unavailable. Retry or choose a map pin."))
                    }
                })
            }
        } else {
            @Suppress("DEPRECATION")
            withContext(Dispatchers.IO) { geocoder.getFromLocationName(query, 6).orEmpty() }
        }
        return addresses.mapNotNull { suggestion(it, query) }
            .distinctBy { "${it.center.latitude},${it.center.longitude}:${it.address}" }
            .take(6)
    }

    suspend fun reverse(center: GeoCenter): PlaceSuggestion? {
        if (!center.valid || !Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.getDefault())
        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine<List<Address>> { continuation ->
                geocoder.getFromLocation(center.latitude, center.longitude, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        if (continuation.isActive) continuation.resume(addresses)
                    }

                    override fun onError(errorMessage: String?) {
                        if (continuation.isActive) continuation.resume(emptyList())
                    }
                })
            }
        } else {
            @Suppress("DEPRECATION")
            withContext(Dispatchers.IO) {
                runCatching { geocoder.getFromLocation(center.latitude, center.longitude, 1).orEmpty() }
                    .getOrDefault(emptyList())
            }
        }
        return addresses.firstNotNullOfOrNull { suggestion(it, "Selected place") }
    }

    private fun suggestion(address: Address, fallback: String): PlaceSuggestion? {
        if (!address.hasLatitude() || !address.hasLongitude()) return null
        val center = GeoCenter(address.latitude, address.longitude)
        if (!center.valid) return null
        val line = address.getAddressLine(0).orEmpty().ifBlank {
            listOfNotNull(
                address.thoroughfare,
                address.subLocality,
                address.locality,
                address.adminArea,
                address.countryName,
            ).distinct().joinToString(", ")
        }
        val feature = address.featureName?.takeUnless { it.isBlank() || it.all(Char::isDigit) }
        val contextualName = listOfNotNull(
            address.thoroughfare?.takeUnless { it.isBlank() || it.all(Char::isDigit) },
            address.subLocality?.takeUnless { it.isBlank() },
            address.locality?.takeUnless { it.isBlank() },
        ).distinct().joinToString(", ")
        val name = feature
            ?: contextualName.ifBlank { line.substringBefore(',').ifBlank { fallback } }
        return PlaceSuggestion(name.take(120), line.ifBlank { fallback }.take(250), center)
    }
}
