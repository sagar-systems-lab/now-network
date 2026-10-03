package com.sagarsystemslab.nownetwork.feature.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sagarsystemslab.nownetwork.R
import com.sagarsystemslab.nownetwork.designsystem.*

@Composable
fun ExperienceHeader(
    title: String, subtitle: String, area: String,
    onArea: () -> Unit, onNotifications: () -> Unit, onProfile: () -> Unit,
    unread: Int = 0,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = if (title.length > 5) NowType.TitleL else NowType.TitleXL, color = NowColors.Ink950,
                modifier = Modifier.semantics { heading() })
            Surface(onClick = onArea, shape = CircleShape, color = NowColors.InfoSoft,
                border = BorderStroke(1.dp, NowColors.InfoBorder), modifier = Modifier.weight(1f).testTag("SHARED-AREA")) {
                Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(Icons.Outlined.LocationOn, null, tint = NowColors.Blue600, modifier = Modifier.size(15.dp))
                    Text(area.ifBlank { "Browse area" }, style = NowType.LabelM, color = NowColors.Ink800, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Outlined.ExpandMore, null, tint = NowColors.Blue600, modifier = Modifier.size(15.dp))
                }
            }
            HeaderIcon(Icons.Outlined.Notifications, "Notifications${if (unread > 0) ", $unread unread" else ""}", onNotifications, "SHARED-BELL", unread)
            HeaderIcon(Icons.Outlined.PersonOutline, "Open profile", onProfile, "SHARED-AVATAR")
        }
        Text(subtitle, style = NowType.BodyM, color = NowColors.Ink600)
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
        IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = NowColors.Ink950) }
        Column(Modifier.weight(1f)) {
            Text(title, style = NowType.TitleM, color = NowColors.Ink950, modifier = Modifier.semantics { heading() })
            subtitle?.let { Text(it, style = NowType.BodyS, color = NowColors.Ink600) }
        }
        trailing?.invoke()
    }
}

@Composable
fun ExperienceRow(title: String, subtitle: String? = null, icon: ImageVector = Icons.Outlined.Tune,
    onClick: (() -> Unit)? = null, tag: String = title, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().testTag(tag).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .heightIn(min = 60.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = NowShapes.medium, color = NowColors.InfoSoft, border = BorderStroke(1.dp, NowColors.InfoBorder)) {
            Icon(icon, null, Modifier.padding(10.dp).size(22.dp), tint = NowColors.Blue600)
        }
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
    NowGlassCard(emphasized = true) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (refreshing) CircularProgressIndicator(Modifier.size(32.dp), color = NowColors.Blue600, strokeWidth = 3.dp)
            else Icon(Icons.Outlined.Radar, null, Modifier.size(32.dp), tint = NowColors.Blue600)
            Column(Modifier.weight(1f)) {
                Text(if (refreshing) "Scanning nearby states…" else if (cached && count == 0) "Nearby data unavailable" else if (cached) "Saved nearby results" else "Nearby results", style = NowType.LabelL, color = NowColors.Ink950)
                Text(if (refreshing) "Finding fresh information for this area" else if (cached && count == 0) "Check your connection and browse area" else "$count result${if (count == 1) "" else "s"} · ${if (cached) "Reconnect to update" else "Tap to update"}", style = NowType.BodyS, color = NowColors.Ink600)
            }
            IconButton(onRefresh, enabled = !refreshing) { Icon(Icons.Outlined.Refresh, "Refresh nearby", tint = NowColors.Blue600) }
        }
    }
}

@Composable
fun MetricStrip(metrics: List<Pair<String, String>>) {
    NowGlassCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            metrics.forEachIndexed { index, (value, label) ->
                if (index > 0) VerticalDivider(Modifier.height(44.dp), color = NowColors.BorderSubtle)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(value, style = NowType.DataMedium, color = NowColors.Ink950)
                    Text(label, style = NowType.LabelM, color = NowColors.Ink600)
                }
            }
        }
    }
}

@Composable
fun EmptyProofCard(title: String, body: String, onBrowse: () -> Unit, onHelp: () -> Unit, activity: Boolean = false) {
    NowGlassCard(emphasized = true) {
        Image(painterResource(if (activity) R.drawable.now_empty_activity else R.drawable.now_empty_proof), null,
            Modifier.fillMaxWidth().height(160.dp).nowLivePulse(active = true))
        Text(title, style = NowType.TitleM, color = NowColors.Ink950)
        Text(body, style = NowType.BodyM, color = NowColors.Ink600)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(Icons.Outlined.LocationOn to "Find", Icons.Outlined.CameraAlt to "Refresh", Icons.Outlined.Payments to "Earn").forEachIndexed { i, (icon, title) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(icon, null, Modifier.size(30.dp), tint = if (i == 2) NowColors.AgingText else NowColors.Blue600)
                    Text(title, style = NowType.LabelL, color = NowColors.Ink950)
                }
            }
        }
        NowPrimaryButton("Browse areas  →", onBrowse, Modifier.fillMaxWidth().testTag("EARN-BROWSE"))
        ExperienceRow("How earning works", icon = Icons.Outlined.MenuBook, onClick = onHelp)
    }
}
