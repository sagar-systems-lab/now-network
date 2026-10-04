package com.sagarsystemslab.nownetwork.feature.common

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.sagarsystemslab.nownetwork.model.GeoCenter
import kotlinx.coroutines.delay

@Composable
internal fun MapLocationButton(onLocation: (GeoCenter) -> Unit, onMessage: (String?) -> Unit) {
    val context = LocalContext.current
    val latestLocation by rememberUpdatedState(onLocation)
    val latestMessage by rememberUpdatedState(onMessage)
    var locating by remember { mutableStateOf(false) }
    var request by remember { mutableStateOf<CancellationTokenSource?>(null) }
    DisposableEffect(Unit) { onDispose { request?.cancel() } }
    LaunchedEffect(request) {
        val active = request ?: return@LaunchedEffect
        delay(20_000)
        if (locating) {
            active.cancel(); locating = false
            latestMessage("Location timed out. Check device location and try again.")
        }
    }
    fun locate() {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return
        request?.cancel()
        val active = CancellationTokenSource()
        request = active; locating = true; latestMessage(null)
        try {
            LocationServices.getFusedLocationProviderClient(context)
                .getCurrentLocation(if (fine) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY, active.token)
                .addOnSuccessListener { position ->
                    if (active.token.isCancellationRequested) return@addOnSuccessListener
                    locating = false
                    val center = position?.let { GeoCenter(it.latitude, it.longitude) }?.takeIf { it.valid }
                    if (center == null) latestMessage("Couldn't find your location. Turn on device location and try again.")
                    else latestLocation(center)
                }
                .addOnFailureListener {
                    if (!active.token.isCancellationRequested) {
                        locating = false
                        latestMessage("Location is unavailable. You can still move the map manually.")
                    }
                }
        } catch (_: SecurityException) {
            locating = false
            latestMessage("Location permission is needed to find your position.")
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) locate()
        else latestMessage("Location permission was not granted. You can still move the map manually.")
    }
    FilledTonalIconButton(onClick = {
        permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }, enabled = !locating) {
        if (locating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Icon(Icons.Outlined.MyLocation, "Find my current location")
    }
}
