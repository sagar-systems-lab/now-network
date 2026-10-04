package com.sagarsystemslab.nownetwork.feature.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import kotlinx.coroutines.isActive
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sagarsystemslab.nownetwork.R
import com.sagarsystemslab.nownetwork.designsystem.*
import com.sagarsystemslab.nownetwork.experience.RemoteAvatar

data class HeaderIdentity(val avatarUrl: String = "", val displayName: String = "N")
val LocalHeaderIdentity = staticCompositionLocalOf { HeaderIdentity() }

@Composable
fun ExperienceHeader(
    title: String, subtitle: String, area: String,
    onArea: () -> Unit, onNotifications: () -> Unit, onProfile: () -> Unit,
    unread: Int = 0,
) {
    val largeText = LocalDensity.current.fontScale >= 1.5f
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = if (title.length > 5) NowType.TitleL else NowType.TitleXL, color = NowColors.Ink950,
                modifier = Modifier.then(if (largeText) Modifier.weight(1f) else Modifier).semantics { heading() })
            if (!largeText) AreaPill(area, onArea, Modifier.weight(1f))
            HeaderIcon(Icons.Outlined.Notifications, "Notifications${if (unread > 0) ", $unread unread" else ""}", onNotifications, "SHARED-BELL", unread)
            val identity = LocalHeaderIdentity.current
            IconButton(onProfile, Modifier.testTag("SHARED-AVATAR").semantics { contentDescription = "Open profile" }) {
                RemoteAvatar(identity.avatarUrl, identity.displayName, 34)
            }
        }
        Text(subtitle, style = NowType.BodyM, color = NowColors.Ink600)
        if (largeText) AreaPill(area, onArea, Modifier.fillMaxWidth())
    }
}

@Composable
private fun AreaPill(area: String, onArea: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.heightIn(min = 48.dp).testTag("SHARED-AREA").clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onArea).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
      Surface(shape = CircleShape, color = NowColors.InfoSoft, border = BorderStroke(1.dp, NowColors.InfoBorder)) {
        Row(Modifier.heightIn(min = 32.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(Icons.Outlined.LocationOn, null, tint = NowColors.Blue600, modifier = Modifier.size(15.dp))
            Text(area.ifBlank { "Browse area" }, style = NowType.LabelM, color = NowColors.Ink800, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Outlined.ExpandMore, null, tint = NowColors.Blue600, modifier = Modifier.size(15.dp))
        }
      }
    }
}

@Composable
private fun HeaderIcon(icon: ImageVector, label: String, onClick: () -> Unit, tag: String, badge: Int = 0) {
    Box {
        IconButton(onClick, Modifier.testTag(tag)) {
            Surface(shape = CircleShape, color = NowColors.SurfacePrimary, border = BorderStroke(1.dp, NowColors.InfoBorder)) {
                Icon(icon, label, Modifier.padding(9.dp).size(21.dp), tint = NowColors.Ink700)
            }
        }
        if (badge > 0) Box(Modifier.padding(top = 7.dp, end = 6.dp).align(Alignment.TopEnd).size(8.dp).background(NowColors.StaleDot, CircleShape))
    }
}

@Composable
fun ExperienceTopBar(title: String, onBack: () -> Unit, subtitle: String? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack) {
            Surface(shape = CircleShape, color = NowColors.SurfacePrimary, border = BorderStroke(1.dp, NowColors.InfoBorder), shadowElevation = 2.dp) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", Modifier.padding(8.dp).size(20.dp), tint = NowColors.Ink950)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = NowType.TitleM, color = NowColors.Ink950, modifier = Modifier.semantics { heading() })
            subtitle?.let { Text(it, style = NowType.BodyS, color = NowColors.Ink600) }
        }
        trailing?.invoke()
    }
}

@Composable
fun ExperienceRow(title: String, subtitle: String? = null, icon: ImageVector = Icons.Outlined.Tune,
    onClick: (() -> Unit)? = null, tag: String = title, compact: Boolean = true, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().testTag(tag).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .heightIn(min = if (compact) 48.dp else 60.dp).padding(vertical = if (compact) 4.dp else 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LuminousIcon(icon, Modifier.size(if (compact) 34.dp else 42.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = NowType.LabelL, color = NowColors.Ink950)
            subtitle?.let { Text(it, style = NowType.BodyS, color = NowColors.Ink600) }
        }
        when {
            trailing != null -> trailing()
            onClick != null -> Icon(Icons.Outlined.ChevronRight, null, tint = NowColors.Ink500)
        }
    }
}

@Composable
fun SyncStrip(refreshing: Boolean, count: Int, cached: Boolean, onRefresh: () -> Unit) {
    NowGlassCard(emphasized = true, contentPadding = 8.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (refreshing) CircularProgressIndicator(Modifier.padding(start = 4.dp).size(30.dp), color = NowColors.Blue600, strokeWidth = 3.dp)
            else Icon(Icons.Outlined.Radar, null, Modifier.padding(start = 4.dp).size(30.dp), tint = NowColors.Blue600)
            Column(Modifier.weight(1f)) {
                Text(if (refreshing) "Scanning nearby states…" else if (cached && count == 0) "Nearby data unavailable" else if (cached) "Saved nearby results" else "Nearby results", style = NowType.LabelL, color = NowColors.Ink950)
                Text(if (refreshing) "Finding fresh information for this area" else if (cached && count == 0) "Check your connection and browse area" else "$count result${if (count == 1) "" else "s"} · ${if (cached) "Reconnect to update" else "Tap to update"}", style = NowType.BodyS, color = NowColors.Ink600)
            }
            IconButton(onRefresh, enabled = !refreshing) { Icon(Icons.Outlined.Refresh, "Refresh nearby", tint = NowColors.Blue600) }
        }
    }
}

@Composable
fun MetricStrip(metrics: List<Pair<String, String>>, framed: Boolean = true, onMetric: ((Int) -> Unit)? = null) {
    if (framed) NowGlassCard(contentPadding = 12.dp) { MetricValues(metrics, onMetric) }
    else MetricValues(metrics, onMetric)
}

@Composable
private fun MetricValues(metrics: List<Pair<String, String>>, onMetric: ((Int) -> Unit)?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            metrics.forEachIndexed { index, (value, label) ->
                if (index > 0) VerticalDivider(Modifier.height(44.dp), color = NowColors.BorderSubtle)
                Column(Modifier.weight(1f).heightIn(min = 48.dp).then(if (onMetric == null) Modifier else Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button) { onMetric(index) }), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val kind = label.lowercase()
                    val icon = when {
                        "wallet" in kind || "balance" in kind || "reward" in kind -> Icons.Outlined.AccountBalanceWallet
                        "progress" in kind -> Icons.Outlined.Schedule
                        "proof" in kind || "complete" in kind -> Icons.Outlined.Verified
                        "live" in kind || "nearby" in kind -> Icons.Outlined.LocationOn
                        else -> Icons.Outlined.Layers
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (LocalDensity.current.fontScale < 1.5f) LuminousIcon(icon, Modifier.size(24.dp),
                            if ("progress" in kind || "need" in kind) NowColors.AgingText else if ("live" in kind || "complete" in kind) NowColors.LiveText else NowColors.Blue600)
                        Text(value, Modifier.weight(1f), style = NowType.DataMedium, color = NowColors.Ink950)
                    }
                    Text(label, style = NowType.LabelM, color = NowColors.Ink600)
                }
            }
    }
}

@Composable
fun EmptyProofCard(title: String, body: String, onBrowse: () -> Unit, onHelp: () -> Unit, activity: Boolean = false) {
    NowGlassCard(emphasized = true, spacing = 8.dp) {
        ProofArtwork(activity, Modifier.fillMaxWidth().height(if (activity) 190.dp else 140.dp), animate = activity)
        Text(title, Modifier.fillMaxWidth(), style = NowType.TitleM, color = NowColors.Ink950, textAlign = TextAlign.Center)
        Text(body, Modifier.fillMaxWidth(), style = NowType.BodyS, color = NowColors.Ink600, textAlign = TextAlign.Center)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(Icons.Outlined.LocationOn to "Find", Icons.Outlined.CameraAlt to "Refresh", Icons.Outlined.Payments to "Earn").forEachIndexed { i, (icon, title) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LuminousIcon(icon, Modifier.size(34.dp), if (i == 2) NowColors.AgingText else if(i == 0) NowColors.LiveText else NowColors.Blue600)
                    Text(title, style = NowType.LabelL, color = NowColors.Ink950)
                }
            }
        }
        NowPrimaryButton("Browse areas  →", onBrowse, Modifier.fillMaxWidth().testTag("EARN-BROWSE"))
        ExperienceRow("How earning works", icon = Icons.Outlined.MenuBook, onClick = onHelp)
    }
}

@Composable
fun LuminousIcon(icon: ImageVector, modifier: Modifier = Modifier.size(36.dp), tint: Color? = null) {
    val accent = tint ?: NowColors.Blue600
    val dark = MaterialTheme.colorScheme.background.red < .2f
    Box(modifier.background(Brush.radialGradient(listOf(accent.copy(alpha = if (dark) .36f else .18f), accent.copy(alpha = .04f))), CircleShape), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            drawCircle(accent.copy(alpha = .28f), radius = size.minDimension * .40f, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        }
        Icon(icon, null, Modifier.fillMaxSize(.55f), tint = accent)
    }
}

/** Original transparent artwork; only its layer moves, and only while visible. */
@Composable
fun ProofArtwork(activity: Boolean, modifier: Modifier = Modifier, animate: Boolean = false) {
    val motion = rememberNowMotionEnabled()
    var visible by remember { mutableStateOf(false) }
    val float = remember { Animatable(0f) }
    LaunchedEffect(motion, animate, visible) {
        float.snapTo(0f)
        if (motion && animate && visible) while (isActive) {
            float.animateTo(-2f, tween(1600, easing = FastOutSlowInEasing))
            float.animateTo(0f, tween(1600, easing = FastOutSlowInEasing))
        }
    }
    val glow = NowColors.Blue500
    Box(modifier.onGloballyPositioned { visible = it.boundsInWindow().let { bounds -> bounds.width > 0f && bounds.height > 0f } }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = .15f), Color.Transparent), center, size.minDimension * .75f), size.minDimension * .75f)
        }
        Image(painterResource(if (activity) R.drawable.now_empty_activity else R.drawable.now_empty_proof), null,
            Modifier.fillMaxSize().graphicsLayer { translationY = float.value.dp.toPx() }, contentScale = ContentScale.Fit)
    }
}
