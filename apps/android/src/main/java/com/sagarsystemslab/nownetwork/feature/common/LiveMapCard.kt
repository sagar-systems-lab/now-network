package com.sagarsystemslab.nownetwork.feature.common

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sagarsystemslab.nownetwork.designsystem.*
import com.sagarsystemslab.nownetwork.model.GeoCenter
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.Style

data class LiveMapPin(val id: String, val title: String, val center: GeoCenter, val status: String)

/** Native vector map; only server coordinates become markers. No synthetic location or map image. */
@Composable
fun LiveMapCard(
    center: GeoCenter?, pins: List<LiveMapPin>, onPin: (String) -> Unit,
    modifier: Modifier = Modifier,
    onSearchArea: ((GeoCenter) -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val dark = MaterialTheme.colorScheme.background.red < .2f
    val latestOnPin by rememberUpdatedState(onPin)
    val latestCenter by rememberUpdatedState(center)
    var pendingCenter by remember { mutableStateOf<GeoCenter?>(null) }
    var selectedPin by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var cameraSnapshot by rememberSaveable(center) { mutableStateOf<DoubleArray?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var tilted by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    val motion = rememberNowMotionEnabled()
    val mapView = remember(context) {
        MapLibre.getInstance(context)
        MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true)).apply { onCreate(Bundle()) }
    }
    DisposableEffect(mapView, lifecycle) {
        // A newly composed map may enter while its lifecycle is already resumed.
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); mapView.onPause(); mapView.onStop(); mapView.onDestroy() }
    }
    LaunchedEffect(mapView) {
        mapView.addOnDidFailLoadingMapListener { failed = true; loaded = false }
        mapView.getMapAsync { ready ->
            ready.uiSettings.isCompassEnabled = false
            ready.uiSettings.isLogoEnabled = false
            ready.uiSettings.isAttributionEnabled = true
            ready.setOnMarkerClickListener { marker -> selectedPin = marker.snippet; true }
            ready.addOnCameraIdleListener {
                ready.cameraPosition.target?.let { target ->
                    cameraSnapshot = doubleArrayOf(target.latitude,target.longitude,ready.cameraPosition.zoom,ready.cameraPosition.tilt)
                    val origin = latestCenter
                    val distance = FloatArray(1)
                    if (origin != null) android.location.Location.distanceBetween(origin.latitude,origin.longitude,target.latitude,target.longitude,distance)
                    pendingCenter = if (origin != null && distance[0] > 100) GeoCenter(target.latitude,target.longitude) else null
                }
            }
            map = ready
        }
    }
    LaunchedEffect(map, dark, retry) {
        val ready = map ?: return@LaunchedEffect
        loaded = false; failed = false
        val json = context.assets.open("maps/${if (dark) "night" else "day"}.json").bufferedReader().use { it.readText() }
        ready.setStyle(Style.Builder().fromJson(json)) { loaded = true }
    }
    LaunchedEffect(map, center, tilted) {
        val c = center?.takeIf { it.valid } ?: pins.firstOrNull()?.center ?: return@LaunchedEffect
        val saved = cameraSnapshot
        map?.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder()
            .target(if(saved != null) LatLng(saved[0], saved[1]) else LatLng(c.latitude, c.longitude)).zoom(saved?.get(2) ?: 13.4).tilt(if (tilted) 38.0 else 0.0).build()))
    }
    LaunchedEffect(map, pins, loaded) {
        val ready = map ?: return@LaunchedEffect
        if (selectedPin != null && pins.none { it.id == selectedPin }) selectedPin = null
        ready.clear()
        pins.filter { it.center.valid }.forEach { pin ->
            val color = when (pin.status.uppercase()) {
                "LIVE", "CLAIMABLE" -> android.graphics.Color.rgb(33, 211, 149)
                "AGING" -> android.graphics.Color.rgb(244, 185, 55)
                "STALE", "CONFLICT" -> android.graphics.Color.rgb(238, 89, 120)
                else -> android.graphics.Color.rgb(127, 152, 183)
            }
            ready.addMarker(MarkerOptions().position(LatLng(pin.center.latitude, pin.center.longitude))
                .title(pin.title).snippet(pin.id).icon(IconFactory.getInstance(context).fromBitmap(pinBitmap(color))))
        }
    }
    Surface(modifier.fillMaxWidth().height(if (expanded) 420.dp else 230.dp).testTag("LIVE-MAP"),
        shape = NowShapes.extraLarge, color = NowColors.SurfaceRaised, border = BorderStroke(1.dp, NowColors.InfoBorder)) {
        Box {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            if (center == null && pins.isEmpty()) {
                Surface(Modifier.align(Alignment.Center).padding(20.dp), shape = NowShapes.medium, color = NowColors.SurfacePrimary) {
                    Text("Choose an area to explore the map", Modifier.padding(14.dp), color = NowColors.Ink700, style = NowType.BodyM)
                }
            }
            if (failed) Surface(Modifier.align(Alignment.BottomCenter).padding(bottom = 30.dp, start = 12.dp, end = 12.dp), color = NowColors.SurfacePrimary, shape = NowShapes.medium) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Map unavailable", Modifier.padding(10.dp), style = NowType.BodyS, color = NowColors.Ink800)
                    TextButton({ retry++ }) { Text("Retry") }
                }
            }
            val selected = pins.firstOrNull { it.id == selectedPin }
            if (selected != null) Surface(Modifier.align(Alignment.BottomCenter).padding(start=12.dp,end=12.dp,bottom=28.dp), shape=NowShapes.medium,color=NowColors.SurfacePrimary) {
                Row(Modifier.padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(selected.title,Modifier.weight(1f),style=NowType.LabelL,color=NowColors.Ink950,maxLines=2)
                    TextButton({latestOnPin(selected.id)}) {Text("Open")}
                    TextButton({selectedPin=null}) {Text("Close")}
                }
            } else if (pendingCenter != null && onSearchArea != null) {
                Button({ pendingCenter?.let(onSearchArea); pendingCenter=null },Modifier.align(Alignment.BottomCenter).padding(bottom=28.dp)) {Text("Search this area")}
            }
            Column(Modifier.align(Alignment.TopEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalIconButton(onClick = {
                    val c = center?.takeIf { it.valid } ?: pins.firstOrNull()?.center
                    if (c != null) {
                        val update = CameraUpdateFactory.newLatLngZoom(LatLng(c.latitude, c.longitude), 13.4)
                        if (motion) map?.animateCamera(update, 320) else map?.moveCamera(update)
                    }
                }, enabled = center != null || pins.isNotEmpty()) { Icon(Icons.Outlined.MyLocation, "Recenter browse area") }
                FilledTonalIconButton(onClick = { tilted = !tilted }) { Icon(Icons.Outlined.Layers, if (tilted) "Use flat map" else "Use tilted map") }
                FilledTonalIconButton(onClick = {
                    val positions = pins.filter { it.center.valid }.map { LatLng(it.center.latitude,it.center.longitude) }
                    if (positions.size > 1) map?.moveCamera(CameraUpdateFactory.newLatLngBounds(org.maplibre.android.geometry.LatLngBounds.Builder().includes(positions).build(),60))
                    else positions.firstOrNull()?.let { map?.moveCamera(CameraUpdateFactory.newLatLngZoom(it,15.0)) }
                },enabled=pins.isNotEmpty()) {Icon(Icons.Outlined.CenterFocusStrong,"Fit results")}
                FilledTonalIconButton(onClick = { expanded = !expanded }) { Icon(Icons.Outlined.OpenInFull, if (expanded) "Collapse map" else "Expand map") }
            }
        }
    }
}

private fun pinBitmap(color: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(64, 80, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val path = Path().apply { moveTo(32f, 74f); cubicTo(26f, 62f, 9f, 43f, 9f, 29f); cubicTo(9f, 0f, 55f, 0f, 55f, 29f); cubicTo(55f, 43f, 38f, 62f, 32f, 74f); close() }
    paint.color = color; paint.setShadowLayer(5f, 0f, 0f, color); canvas.drawPath(path, paint)
    paint.clearShadowLayer(); paint.color = android.graphics.Color.WHITE; paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; canvas.drawPath(path, paint)
    paint.style = Paint.Style.FILL; canvas.drawCircle(32f, 28f, 9f, paint)
    return bitmap
}
