package com.sagarsystemslab.nownetwork.feature.common

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sagarsystemslab.nownetwork.designsystem.*
import com.sagarsystemslab.nownetwork.experience.*
import com.sagarsystemslab.nownetwork.feature.state.formatStateValue
import com.sagarsystemslab.nownetwork.model.StateDetail
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.*

@Composable
fun StateHistoryPanel(detail: StateDetail, page: JsonObject?, error: String?, onRefresh: () -> Unit, onMore: () -> Unit) {
    var range by rememberSaveable(detail.stateId) { mutableStateOf("All") }
    var showAll by rememberSaveable(detail.stateId) { mutableStateOf(false) }
    var selected by remember(detail.stateId) { mutableStateOf<JsonObject?>(null) }
    val now = System.currentTimeMillis()
    val source = page?.rows().orEmpty()
    val rows = source.filter { row -> val at = runCatching { Instant.parse(row.text("observed_at")).toEpochMilli() }.getOrDefault(0)
        range == "All" || now - at <= if (range == "24h") 86_400_000 else 604_800_000 }
    val blue = NowColors.Blue600; val border = NowColors.BorderSubtle
    val motion = rememberNowMotionEnabled()
    val reveal = remember(detail.stateId, range) { Animatable(0f) }
    LaunchedEffect(detail.stateId, range, rows.isNotEmpty(), motion) {
        if (rows.isEmpty()) return@LaunchedEffect
        if (motion) reveal.animateTo(1f, tween(300)) else reveal.snapTo(1f)
    }
    NowGlassCard {
        ExperienceRow("State history", "Verified observations · ${source.size} loaded", Icons.Outlined.Timeline)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("24h", "7d", "All").forEach { label -> FilterChip(range == label, { range = label }, label = { Text(label) }) } }
        when {
            error != null -> { Text(error, color = NowColors.StaleText); NowSecondaryButton("Retry history", onRefresh) }
            page == null -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = blue)
            rows.isEmpty() -> Text("No verified observations in this range.", color = NowColors.Ink600, style = NowType.BodyM)
            else -> {
                val numeric = detail.stateType.uppercase() == "NUMERIC"
                if (numeric) {
                    val points = rows.reversed().map { row ->
                        val value = row["value"]
                        val number = (value as? JsonPrimitive)?.doubleOrNull ?: (value as? JsonObject)?.let { obj -> obj.text("scaled_value").toBigDecimalOrNull()?.movePointLeft(obj.number("scale"))?.toDouble() ?: (obj["value"] as? JsonPrimitive)?.doubleOrNull }
                        Triple(runCatching { Instant.parse(row.text("observed_at")).toEpochMilli() }.getOrDefault(0), number?.takeIf { it.isFinite() }, row)
                    }
                    val values = points.mapNotNull { it.second }; val minimum = values.minOrNull() ?: 0.0; val maximum = values.maxOrNull() ?: 1.0
                    Canvas(Modifier.fillMaxWidth().height(110.dp).semantics { contentDescription = "Verified observation chart. Exact values and timestamps are listed below." }) {
                        repeat(3) { i -> val y = size.height * (i + 1) / 4; drawLine(border, Offset(0f, y), Offset(size.width,y)) }
                        val first = points.first().first; val span = (points.last().first - first).coerceAtLeast(1)
                        var previous: Offset? = null
                        clipRect(right = size.width * reveal.value) {
                        points.forEach { (time, value, _) ->
                            val point = value?.let { Offset(if (points.size == 1) size.width / 2 else ((time-first).toDouble()/span*size.width).toFloat(), (size.height - 8.dp.toPx()) - ((it-minimum)/(maximum-minimum).coerceAtLeast(1.0)*(size.height-16.dp.toPx())).toFloat()) }
                            if (point != null) { previous?.let { drawLine(blue, it, point, 2.dp.toPx()) }; drawCircle(blue, 4.dp.toPx(), point) }
                            previous = point // Missing observations break the line; they are never zero-filled.
                        }
                        }
                    }
                }
                rows.take(if(showAll) rows.size else 8).forEach { row -> ExperienceRow(formatStateValue(row["value"]?.toString(), detail.unitCode), displayEventTime(row.text("observed_at")),
                    if (numeric) Icons.Outlined.BarChart else Icons.Outlined.History, { selected = row }) }
                if (rows.size > 8) TextButton({showAll=!showAll}) { Text(if(showAll) "Show recent observations" else "Show all ${rows.size} observations") }
            }
        }
        if (page?.text("next_cursor")?.isNotBlank() == true) NowSecondaryButton("Load earlier observations", onMore, Modifier.fillMaxWidth())
        TextButton(onRefresh) { Text("Refresh history") }
    }
    selected?.let { row -> AlertDialog(onDismissRequest = { selected = null }, title = { Text(formatStateValue(row["value"]?.toString(), detail.unitCode)) },
        text = { Text("Observed ${displayEventTime(row.text("observed_at"))}\n${row.text("verification_class").replace('_',' ')} · ${row.text("verification_status")}") }, confirmButton = { TextButton({ selected = null }) { Text("Done") } }) }
}

fun displayEventTime(value: String): String = runCatching { DateTimeFormatter.ofPattern("d MMM · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value)) }.getOrDefault("Time unavailable")

@Composable
fun LocationAction(detail: StateDetail) {
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val center = detail.location.center?.takeIf { it.valid }
    ExperienceRow(detail.location.name, detail.location.displayAddress, Icons.Outlined.LocationOn, {
        val query = if (center != null) "${center.latitude},${center.longitude}(${detail.location.name})" else detail.location.name + " " + detail.location.displayAddress.orEmpty()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(query)}"))
        try { context.startActivity(Intent.createChooser(intent, "Open location")) }
        catch (_: android.content.ActivityNotFoundException) { clipboard.setText(androidx.compose.ui.text.AnnotatedString(query)) }
    })
}
