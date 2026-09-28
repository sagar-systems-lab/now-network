package com.sagarsystemslab.nownetwork.evidence

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

data class EvidenceLocationSample(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double?,
    val provider: String?,
    val mockSignal: Boolean?,
    val observedElapsedRealtimeMs: Long,
)

sealed class EvidenceLocationFailure(message: String, cause: Throwable? = null) :
    Exception(message, cause) {
    class Permission : EvidenceLocationFailure("Precise location permission is required.")
    class Unavailable(cause: Throwable? = null) :
        EvidenceLocationFailure("A current location fix is not available.", cause)
}

interface EvidenceLocationProvider {
    suspend fun currentSample(): EvidenceLocationSample
}

@Singleton
class FusedEvidenceLocationProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : EvidenceLocationProvider {
    private val client: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    override suspend fun currentSample(): EvidenceLocationSample {
        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            throw EvidenceLocationFailure.Permission()
        }

        val fresh = try {
            currentLocation()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }

        val location = fresh ?: try {
            lastLocation()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw EvidenceLocationFailure.Unavailable(error)
        } ?: throw EvidenceLocationFailure.Unavailable()

        val observedElapsedRealtimeMs = location.elapsedRealtimeNanos / 1_000_000L
        if (
            fresh == null &&
            SystemClock.elapsedRealtime() - observedElapsedRealtimeMs > MAX_LAST_LOCATION_AGE_MS
        ) {
            throw EvidenceLocationFailure.Unavailable()
        }

        return EvidenceLocationSample(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
            provider = location.provider?.take(64),
            mockSignal = LocationCompat.isMock(location),
            observedElapsedRealtimeMs = observedElapsedRealtimeMs,
        )
    }

    private suspend fun currentLocation(): android.location.Location? =
        suspendCancellableCoroutine { continuation ->
            val cancellation = CancellationTokenSource()
            continuation.invokeOnCancellation { cancellation.cancel() }

            try {
                client.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    cancellation.token,
                )
                    .addOnSuccessListener { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }
                    .addOnFailureListener { error ->
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
            } catch (error: SecurityException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }

    private suspend fun lastLocation(): android.location.Location? =
        suspendCancellableCoroutine { continuation ->
            try {
                client.lastLocation
                    .addOnSuccessListener { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }
                    .addOnFailureListener { error ->
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
            } catch (error: SecurityException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    private companion object {
        const val MAX_LAST_LOCATION_AGE_MS = 30_000L
    }
}
