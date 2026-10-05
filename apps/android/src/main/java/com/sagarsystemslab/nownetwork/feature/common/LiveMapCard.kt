package com.sagarsystemslab.nownetwork.feature.common

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
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
import org.maplibre.android.tile.TileOperation

data class LiveMapPin(val id: String, val title: String, val center: GeoCenter, val status: String, val photoStateId: String? = null)

/** Native vector map; only server coordinates become markers. No synthetic location or map image. */
@Composable
fun LiveMapCard(
    center: GeoCenter?, pins: List<LiveMapPin>, onPin: (String) -> Unit,
    modifier: Modifier = Modifier,
    onSearchArea: ((GeoCenter) -> Unit)? = null,
    onLocateArea: ((GeoCenter) -> Unit)? = onSearchArea,
    onMapTap: ((GeoCenter) -> Unit)? = null,
    searchAreaLabel: String = "Search this area",
    minimumPanMeters: Float = 100f,
    animateCenterChanges: Boolean = false,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val dark = MaterialTheme.colorScheme.background.red < .2f
    val latestOnPin by rememberUpdatedState(onPin)
    val latestCenter by rememberUpdatedState(center)
    val latestMapTap by rememberUpdatedState(onMapTap)
    val latestMinimumPan by rememberUpdatedState(minimumPanMeters)
    var pendingCenter by remember { mutableStateOf<GeoCenter?>(null) }
    var selectedPin by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var cameraSnapshot by rememberSaveable { mutableStateOf<DoubleArray?>(null) }
    var cameraReady by remember { mutableStateOf(false) }
    var appliedCenter by remember { mutableStateOf<GeoCenter?>(null) }
    var gestureMoved by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = expanded || selectedPin != null) { if(selectedPin != null) selectedPin=null else expanded = false }
    var tilted by remember { mutableStateOf(true) }
    var loadState by remember { mutableStateOf(MapLoadState()) }
    val failed = loadState.failed
    val loaded = loadState.styleLoaded
    val mapRendered = loadState.ready
    var basicMap by rememberSaveable { mutableStateOf(false) }
    val latestBasicMap by rememberUpdatedState(basicMap)
    var mapMenu by remember { mutableStateOf(false) }
    var locationMessage by remember { mutableStateOf<String?>(null) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    val motion = rememberNowMotionEnabled()
    var moving by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    var centerPoint by remember { mutableStateOf<Offset?>(null) }
    val pulse = remember { Animatable(0f) }
    val compactHeight = if (LocalConfiguration.current.screenHeightDp < 740) 190.dp else 210.dp
    LaunchedEffect(motion, moving, loaded, center, visible) {
        pulse.snapTo(0f)
        if (motion && visible && !moving && loaded && center?.valid == true) while (isActive) {
            pulse.animateTo(1f, tween(2600, easing = LinearEasing))
            pulse.snapTo(0f)
        }
    }
    val mapView = remember(context) {
        MapLibre.getInstance(context)
        val initial = browseCamera(center, cameraSnapshot)
        MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true)
            .camera(CameraPosition.Builder().target(LatLng(initial[0], initial[1])).zoom(initial[2]).build()))
            .apply { onCreate(Bundle()) }
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
        // A tile/network failure must not hide the map that already rendered.
        mapView.addOnDidFailLoadingMapListener { loadState = loadState.failure() }
        mapView.addOnRenderErrorListener { loadState = loadState.failure() }
        mapView.addOnTileActionListener { operation, _, _, _, _, _, source ->
            if (source == if (latestBasicMap) "street-tiles" else "openmaptiles") {
                when (operation) {
                    TileOperation.LoadFromNetwork, TileOperation.LoadFromCache, TileOperation.EndParse -> loadState = loadState.tileReceived()
                    TileOperation.Error -> loadState = loadState.failure()
                    else -> Unit
                }
            }
        }
        mapView.addOnDidFinishRenderingFrameListener { fully: Boolean, _: Double, _: Double ->
            if (cameraReady) loadState = loadState.rendered(fully)
        }
        mapView.getMapAsync { ready ->
            ready.uiSettings.isCompassEnabled = false
            ready.uiSettings.isLogoEnabled = false
            ready.uiSettings.isAttributionEnabled = true
            ready.setMinZoomPreference(1.0)
            ready.setMaxZoomPreference(19.0)
            // Fetch only the visible zoom level, including on the street-map fallback.
            ready.setPrefetchZoomDelta(0)
            ready.uiSettings.isScrollGesturesEnabled = true
            ready.uiSettings.isZoomGesturesEnabled = true
            ready.addOnMapClickListener { point ->
                latestMapTap?.let { it(GeoCenter(point.latitude, point.longitude)); true } ?: false
            }
            ready.setOnMarkerClickListener { marker -> selectedPin = marker.snippet; true }
            fun updateCenterPoint() {
                centerPoint = latestCenter?.takeIf { it.valid }?.let { c ->
                    ready.projection.toScreenLocation(LatLng(c.latitude, c.longitude)).let { Offset(it.x, it.y) }
                }
            }
            ready.addOnCameraMoveStartedListener { reason ->
                moving = true
                loadState = loadState.rendered(false)
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) gestureMoved = true
            }
            ready.addOnCameraMoveListener { updateCenterPoint() }
            ready.addOnCameraIdleListener {
                moving = false
                updateCenterPoint()
                ready.cameraPosition.target?.takeIf { cameraReady && appliedCenter == latestCenter }?.let { target ->
                    cameraSnapshot = saveBrowseCamera(latestCenter, target.latitude, target.longitude, ready.cameraPosition.zoom)
                    val origin = latestCenter
                    val distance = FloatArray(1)
                    if (origin != null) android.location.Location.distanceBetween(origin.latitude,origin.longitude,target.latitude,target.longitude,distance)
                    pendingCenter = if (gestureMoved && (origin == null || distance[0] > latestMinimumPan)) GeoCenter(target.latitude,target.longitude) else null
                }
            }
            map = ready
        }
    }
    LaunchedEffect(map, dark, retry, basicMap) {
        val ready = map ?: return@LaunchedEffect
        loadState = MapLoadState(); cameraReady = false
        val name = if (basicMap) "streets" else if (dark) "night" else "day"
        val json = context.assets.open("maps/$name.json").bufferedReader().use { it.readText() }
        ready.setStyle(Style.Builder().fromJson(json)) { loadState = loadState.styleLoaded() }
    }
    LaunchedEffect(map, loaded, mapRendered, moving, basicMap, retry) {
        if (map == null || moving || mapRendered) return@LaunchedEffect
        delay(12_000)
        if (!mapRendered) {
            if (!basicMap) basicMap = true else loadState = loadState.failure()
        }
    }
    LaunchedEffect(map, center, loaded) {
        val ready = map ?: return@LaunchedEffect
        if (!loaded) return@LaunchedEffect
        cameraReady = false
        gestureMoved = false
        pendingCenter = null
        val position = browseCamera(center?.takeIf { it.valid }, cameraSnapshot)
        val update = CameraUpdateFactory.newCameraPosition(CameraPosition.Builder()
            .target(LatLng(position[0], position[1])).zoom(position[2]).tilt(if (tilted && !basicMap) 38.0 else 0.0).build())
        if (animateCenterChanges && motion && appliedCenter != null && appliedCenter != center) ready.animateCamera(update, 650)
        else ready.moveCamera(update)
        appliedCenter = center
        cameraReady = true
        centerPoint = center?.takeIf { it.valid }?.let { c ->
            ready.projection.toScreenLocation(LatLng(c.latitude, c.longitude)).let { Offset(it.x, it.y) }
        }
    }
    LaunchedEffect(map, tilted, basicMap) {
        val ready = map ?: return@LaunchedEffect
        if (cameraReady) ready.moveCamera(CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder(ready.cameraPosition).tilt(if (tilted && !basicMap) 38.0 else 0.0).build()))
    }
    LaunchedEffect(map, pins, loaded, selectedPin) {
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
                .title(pin.title).snippet(pin.id).icon(IconFactory.getInstance(context).fromBitmap(pinBitmap(color, pin.id == selectedPin))))
        }
    }
    Surface(modifier.fillMaxWidth().height(if (expanded) 420.dp else compactHeight)
        .onGloballyPositioned { coordinates ->
            visible = coordinates.boundsInWindow().let { it.width > 0f && it.height > 0f }
        }.testTag("LIVE-MAP"),
        shape = NowShapes.extraLarge, color = NowColors.SurfaceRaised, border = BorderStroke(1.dp, NowColors.InfoBorder)) {
        Box {
            AndroidView(factory = { MapGestureFrame(it).apply { addView(mapView, android.widget.FrameLayout.LayoutParams(-1, -1)) } },
                modifier = Modifier.fillMaxSize())
            if (loaded && center?.valid == true) androidx.compose.foundation.Canvas(
                Modifier.fillMaxSize().semantics { contentDescription = "Selected browse area center" },
            ) {
                centerPoint?.let { point ->
                    val blue = Color(0xFF168BFF)
                    drawCircle(Brush.radialGradient(listOf(blue.copy(alpha = .34f), Color.Transparent), point, 42.dp.toPx()), 42.dp.toPx(), point)
                    if (motion && visible && !moving) repeat(2) { ring ->
                        val phase = (pulse.value + ring * .5f) % 1f
                        drawCircle(blue.copy(alpha = (1f - phase) * .45f),
                            (14 + phase * 28).dp.toPx(), point, style = Stroke(1.5.dp.toPx()))
                    } else drawCircle(blue.copy(alpha = .35f), 25.dp.toPx(), point, style = Stroke(1.5.dp.toPx()))
                    drawCircle(Color.White, 8.dp.toPx(), point)
                    drawCircle(blue, 6.dp.toPx(), point)
                }
            }
            if (!mapRendered && !failed && !moving) Surface(Modifier.align(Alignment.TopCenter).padding(top = 12.dp, start = 64.dp, end = 64.dp), color = NowColors.SurfacePrimary, shape = NowShapes.medium) {
                Text(if (basicMap) "Loading street map…" else "Loading map details…", Modifier.padding(8.dp).testTag("MAP-LOADING"), style = NowType.BodyS, color = NowColors.Ink700)
            }
            if (center == null && pins.isEmpty()) {
                Surface(Modifier.align(Alignment.Center).padding(20.dp), shape = NowShapes.medium, color = NowColors.SurfacePrimary) {
                    Text("Choose an area to explore the map", Modifier.padding(14.dp), color = NowColors.Ink700, style = NowType.BodyM)
                }
            }
            if (failed) Surface(Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp, start = 12.dp, end = 12.dp), color = NowColors.SurfacePrimary, shape = NowShapes.medium) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Map details couldn't load", Modifier.weight(1f).padding(10.dp), style = NowType.BodyS, color = NowColors.Ink800)
                    TextButton({ if (!basicMap) basicMap = true else retry++ }) { Text(if (basicMap) "Retry" else "Street map") }
                }
            }
            val selected = pins.firstOrNull { it.id == selectedPin }
            if (selected != null && !failed) Surface(Modifier.align(Alignment.BottomCenter).padding(start=12.dp,end=12.dp,bottom=28.dp), shape=NowShapes.medium,color=NowColors.SurfacePrimary) {
                Row(Modifier.padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    selected.photoStateId?.let { stateId ->
                        com.sagarsystemslab.nownetwork.experience.StatePhoto(stateId,selected.title,
                            Modifier.padding(end=8.dp).size(48.dp),fallback={})
                    }
                    Column(Modifier.weight(1f)) {
                        Text(selected.title,style=NowType.LabelL,color=NowColors.Ink950,maxLines=2)
                        Text(when(selected.status.uppercase()) {
                            "LIVE" -> "Fresh verified update"; "AGING" -> "Update aging"; "STALE" -> "Needs a fresh update"
                            "CLAIMABLE" -> "Available to contribute"; "CONFLICT" -> "Conflicting proof"; else -> "No fresh proof yet"
                        },style=NowType.BodyS,color=NowColors.Ink600)
                    }
                    TextButton({latestOnPin(selected.id)}) {Text("Open")}
                    TextButton({selectedPin=null}) {Text("Close")}
                }
            } else if (pendingCenter != null && onSearchArea != null && !failed && mapRendered) {
                Button({ pendingCenter?.let(onSearchArea); pendingCenter=null },Modifier.align(Alignment.BottomCenter).padding(bottom=28.dp)) {Text(searchAreaLabel)}
            }
            Column(Modifier.align(Alignment.TopEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MapLocationButton(onLocation = { c ->
                        cameraSnapshot = null
                        gestureMoved = false
                        pendingCenter = null
                        val update = CameraUpdateFactory.newLatLngZoom(LatLng(c.latitude, c.longitude), 13.4)
                        if (motion) map?.animateCamera(update, 320) else map?.moveCamera(update)
                        onLocateArea?.invoke(c)
                }, onMessage = { locationMessage = it })
                Box {
                    FilledTonalIconButton(onClick = { mapMenu = true }) { Icon(Icons.Outlined.Layers, "Map options") }
                    DropdownMenu(mapMenu, { mapMenu = false }) {
                        DropdownMenuItem(text = { Text("Detailed map") }, onClick = { basicMap = false; retry++; mapMenu = false })
                        DropdownMenuItem(text = { Text("Basic street map") }, onClick = { basicMap = true; retry++; mapMenu = false })
                        DropdownMenuItem(text = { Text(if (tilted) "Flat view" else "Tilted view") }, enabled = !basicMap, onClick = { tilted = !tilted; mapMenu = false })
                        DropdownMenuItem(text = { Text("Open area in Maps") }, onClick = {
                            map?.cameraPosition?.target?.let { point ->
                                val uri=android.net.Uri.parse("geo:${point.latitude},${point.longitude}?q=${point.latitude},${point.longitude}")
                                try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,uri)) }
                                catch (_: android.content.ActivityNotFoundException) { locationMessage="No maps app is installed on this device." }
                            }
                            mapMenu=false
                        })
                        DropdownMenuItem(text = { Text("Reset north") }, onClick = {
                            map?.let { ready -> ready.moveCamera(CameraUpdateFactory.newCameraPosition(
                                CameraPosition.Builder(ready.cameraPosition).bearing(0.0).build())) }
                            mapMenu = false
                        })
                    }
                }
                FilledTonalIconButton(onClick = {
                    val positions = pins.filter { it.center.valid }.map { LatLng(it.center.latitude,it.center.longitude) }
                    if (positions.size > 1) map?.moveCamera(CameraUpdateFactory.newLatLngBounds(org.maplibre.android.geometry.LatLngBounds.Builder().includes(positions).build(),60))
                    else positions.firstOrNull()?.let { map?.moveCamera(CameraUpdateFactory.newLatLngZoom(it,15.0)) }
                },enabled=pins.isNotEmpty()) {Icon(Icons.Outlined.CenterFocusStrong,"Fit results")}
            }
            Column(Modifier.align(Alignment.TopStart).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalIconButton(onClick = { expanded = !expanded }) {
                    Icon(Icons.Outlined.OpenInFull, if (expanded) "Collapse map" else "Expand map")
                }
                FilledTonalIconButton(onClick = {
                    if (motion) map?.animateCamera(CameraUpdateFactory.zoomIn(), 220) else map?.moveCamera(CameraUpdateFactory.zoomIn())
                }, enabled = loaded) { Icon(Icons.Outlined.Add, "Zoom in") }
                FilledTonalIconButton(onClick = {
                    if (motion) map?.animateCamera(CameraUpdateFactory.zoomOut(), 220) else map?.moveCamera(CameraUpdateFactory.zoomOut())
                }, enabled = loaded) { Icon(Icons.Outlined.Remove, "Zoom out") }
            }
            Surface(Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 4.dp), color = NowColors.SurfacePrimary.copy(alpha = .92f), shape = NowShapes.small) {
                Text(if (basicMap) "© OpenStreetMap contributors" else "© OpenStreetMap · OpenFreeMap", Modifier.padding(horizontal = 5.dp, vertical = 2.dp), style = NowType.BodyS, color = NowColors.Ink700)
            }
        }
    }
    locationMessage?.let { message ->
        AlertDialog(onDismissRequest = { locationMessage = null }, title = { Text("Map") },
            text = { Text(message) }, confirmButton = { TextButton({ locationMessage = null }) { Text("OK") } })
    }
}

private fun pinBitmap(color: Int, selected: Boolean = false): Bitmap {
    val bitmap = Bitmap.createBitmap(64, 80, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    if (selected) canvas.scale(1.08f, 1.08f, 32f, 74f)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val path = Path().apply { moveTo(32f, 74f); cubicTo(26f, 62f, 9f, 43f, 9f, 29f); cubicTo(9f, 0f, 55f, 0f, 55f, 29f); cubicTo(55f, 43f, 38f, 62f, 32f, 74f); close() }
    paint.color = color; paint.setShadowLayer(5f, 0f, 0f, color); canvas.drawPath(path, paint)
    paint.clearShadowLayer(); paint.color = android.graphics.Color.WHITE; paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; canvas.drawPath(path, paint)
    paint.style = Paint.Style.FILL; canvas.drawCircle(32f, 28f, 9f, paint)
    return bitmap
}
