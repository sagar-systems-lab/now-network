package com.sagarsystemslab.nownetwork.experience

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.sagarsystemslab.nownetwork.designsystem.*
import com.sagarsystemslab.nownetwork.feature.common.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

@Composable
fun BrowseAreasScreen(state: ExperienceUiState, viewModel: ExperienceViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    var locationMessage by remember { mutableStateOf<String?>(null) }
    var locating by remember { mutableStateOf(false) }
    var cancellation by remember { mutableStateOf(CancellationTokenSource()) }
    LaunchedEffect(locating, cancellation) { if (locating) { delay(30_000); cancellation.cancel(); locating = false; locationMessage = "Location timed out. Try again or choose an area below." } }
    DisposableEffect(Unit) { onDispose { cancellation.cancel() } }
    fun locate() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        cancellation.cancel()
        cancellation = CancellationTokenSource()
        val request = cancellation
        locating = true
        locationMessage = null
        LocationServices.getFusedLocationProviderClient(context).getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cancellation.token)
            .addOnSuccessListener { location ->
                if (request.token.isCancellationRequested) return@addOnSuccessListener
                locating = false
                if (location == null) locationMessage = "Couldn't find your position. Choose a supported area below."
                else { viewModel.browseContext.select("Near my location", location.latitude, location.longitude); onBack() }
            }.addOnFailureListener { if (request.token.isCancellationRequested) return@addOnFailureListener; locating = false; locationMessage = "Location is unavailable. You can still choose an area below." }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) locate() else locationMessage = "Location permission was not granted. Browse manually below."
    }
    LaunchedEffect(query, filter) { delay(250); viewModel.areas(query, filter = when (filter) { "Live" -> "live"; "Needs proof" -> "stale"; else -> "all" }) }
    val visible = state.areas
    LazyColumn(Modifier.fillMaxSize().testTag("screen-browse-areas"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ExperienceTopBar("Browse areas", onBack, "Choose where you want to explore") }
        item { NowTextField(query, { query = it }, "Search an area", supportingText = "Search the live location catalog") }
        item { NowGlassCard(emphasized = true) { ExperienceRow("Use my current location", if (locating) "Finding your position…" else "Find states around you", Icons.Outlined.MyLocation, onClick = {
            if (!locating) permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
        }) } }
        locationMessage?.let { message -> item { NowNotice(message) } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("All", "Live", "Needs proof").forEach { label -> FilterChip(filter == label, { filter = label }, label = { Text(label) }) } } }
        item { NowSectionTitle("Supported areas", "Counts cover each area's 3 km browse radius") }
        if (state.areasLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = NowColors.Blue600) }
        state.areaError?.let { message -> item { NowNotice(message, tone = NowNoticeTone.ERROR); NowSecondaryButton("Try again", { viewModel.areas(query) }) } }
        items(visible, key = { it.text("area_id") }) { area ->
            NowGlassCard {
                ExperienceRow(area.text("name"), area.text("display_address").ifBlank { "3 km browse area" }, Icons.Outlined.LocationOn, { viewModel.selectArea(area); onBack() })
                MetricStrip(listOf(area.number("total").toString() to "States", area.number("live").toString() to "Live", (area.number("stale") + area.number("unobserved")).toString() to "Need proof"))
                NowPrimaryButton("Explore this area", { viewModel.selectArea(area); onBack() }, Modifier.fillMaxWidth().testTag("AREA-SELECT-${area.text("area_id")}"))
            }
        }
        if (visible.isEmpty() && !state.areasLoading && state.areaError == null) item {
            NowGlassCard(emphasized = true) {
                ProofArtwork(false, Modifier.fillMaxWidth().height(140.dp))
                Text(if (query.isBlank() && filter == "All") "Explore from where you are" else "No matching areas", style = NowType.TitleM, color = NowColors.Ink950)
                Text(if (query.isBlank() && filter == "All") "No supported places are listed yet. Use your current location to center the map; nearby states appear when places are added." else "Try another area name or clear your filters.", style = NowType.BodyM, color = NowColors.Ink600)
                if (query.isNotBlank() || filter != "All") NowSecondaryButton("Clear search & filters", { query = ""; filter = "All" }, Modifier.fillMaxWidth())
            }
        }
        if (state.nextAreaOffset != null) item { NowSecondaryButton("Load more areas", { viewModel.areas(query, more = true) }, Modifier.fillMaxWidth(), enabled = !state.areasLoading) }
    }
}

@Composable
fun NotificationsScreen(state: ExperienceUiState, viewModel: ExperienceViewModel, onBack: () -> Unit, onPreferences: () -> Unit, onOpen: (JsonObject) -> Unit) {
    var filter by rememberSaveable { mutableStateOf("all") }
    val rows = state.inbox.rows()
    val motionDuration = nowMotionDuration(rememberNowMotionEnabled(), 180)
    LazyColumn(Modifier.fillMaxSize().testTag("screen-notifications"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ExperienceTopBar("Notifications", onBack, "${state.inbox.number("unread_count")} unread updates", trailing = { IconButton(onPreferences) { Icon(Icons.Outlined.Tune, "Notification preferences", tint = NowColors.Blue600) } }) }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("all", "unread", "proof", "payments", "security", "opportunities").forEach { category ->
                    FilterChip(filter == category, { filter = category; viewModel.inbox(category) }, label = { Text(category.replaceFirstChar { it.uppercase() }) }, enabled = !state.saving && !state.loading)
                }
            }
            TextButton({ viewModel.markRead() }, enabled = state.inbox.number("unread_count") > 0 && !state.saving) { Text("Mark all read") }
        }
        items(rows, key = { it.text("notification_id") }) { notification ->
            val unread = notification.text("read_at").isBlank()
            NowGlassCard(Modifier.animateItem(fadeInSpec = tween(motionDuration), fadeOutSpec = tween(motionDuration), placementSpec = tween(motionDuration)), emphasized = unread, spacing = 4.dp) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (unread) Box(Modifier.size(7.dp).background(NowColors.Blue600, CircleShape))
                    Text(notification.text("category").replaceFirstChar { it.uppercase() }, Modifier.weight(1f), style = NowType.LabelM, color = NowColors.Ink600)
                    Text(displayEventTime(notification.text("created_at")), style = NowType.BodyS, color = NowColors.Ink500)
                }
                ExperienceRow(notification.text("title"), notification.text("body"), when (notification.text("category")) {
                    "payments" -> Icons.Outlined.Payments
                    "security" -> Icons.Outlined.Shield
                    "opportunities" -> Icons.Outlined.WorkOutline
                    else -> Icons.Outlined.Verified
                }, onClick = { viewModel.markRead(notification.text("notification_id")); onOpen(notification) })
                if (unread) TextButton({ viewModel.markRead(notification.text("notification_id")) }, enabled = !state.saving) { Text("Mark read") }
            }
        }
        if (rows.isEmpty() && !state.loading && !state.saving) item {
            NowGlassCard(emphasized = true) {
                ProofArtwork(false, Modifier.fillMaxWidth().height(150.dp))
                Text(if (state.error == null && state.inbox.isNotEmpty()) "You're all caught up" else "Your updates belong here", style = NowType.TitleM, color = NowColors.Ink950)
                Text(if (state.error == null) "Proof progress, payments and account updates, together in one place." else "Updates couldn't load. Reconnect to see your account events.", style = NowType.BodyM, color = NowColors.Ink600)
                ExperienceRow("Proof & refresh progress", "Know when an observation is verified", Icons.Outlined.Verified)
                ExperienceRow("Payments & receipts", "Follow settlement through to your receipt", Icons.Outlined.ReceiptLong)
                NowSecondaryButton("Notification preferences", onPreferences, Modifier.fillMaxWidth())
            }
        }
        item { ExperienceFeedback(state, viewModel) }
        if (state.inbox.text("next_offset").isNotBlank()) item { NowSecondaryButton("Load earlier updates", { viewModel.inbox(filter, more = true) }, Modifier.fillMaxWidth(), enabled = !state.saving) }
        item { NowSecondaryButton("Refresh notifications", { viewModel.inbox(filter) }, Modifier.fillMaxWidth(), enabled = !state.saving) }
    }
}
