package com.sagarsystemslab.nownetwork.feature.common

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import kotlinx.coroutines.isActive
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sagarsystemslab.nownetwork.BuildConfig
import com.sagarsystemslab.nownetwork.designsystem.*
import kotlin.math.*

@Composable
fun TransactionPage(title: String, subtitle: String, tag: String, onBack: () -> Unit,
    footer: @Composable ColumnScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().testTag(tag).imePadding()) {
        Box(Modifier.padding(horizontal = 8.dp)) { ExperienceTopBar(title, onBack, subtitle) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        Surface(color = NowColors.SurfaceCanvas, shadowElevation = 12.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = footer)
        }
    }
}

/** A pending orbit and a single persisted success reveal; all information also has text. */
@Composable
fun ResultEmblem(
    success: Boolean,
    active: Boolean = false,
    modifier: Modifier = Modifier,
    eventKey: String? = null,
) {
    val enabled = rememberNowMotionEnabled()
    val resumed = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentState
        .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    var visible by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val seen = remember(context) { context.getSharedPreferences("now_seen_results", android.content.Context.MODE_PRIVATE) }
    var firstResult by remember(eventKey) { mutableStateOf(eventKey != null && !seen.getBoolean(eventKey, false)) }
    val reveal = remember(eventKey, success) { Animatable(if (success && firstResult && enabled) 0f else 1f) }
    val orbit = remember { Animatable(0f) }
    val receiptReveal = eventKey?.startsWith("receipt:") == true
    LaunchedEffect(success, enabled, eventKey, visible, resumed) {
        if (success && firstResult && visible && resumed) {
            firstResult = false
            eventKey?.let { seen.edit().putBoolean(it, true).apply() }
            if (enabled) { reveal.snapTo(0f); reveal.animateTo(1f, tween(if (receiptReveal) 650 else 480)) } else reveal.snapTo(1f)
        } else if (!firstResult || !enabled) reveal.snapTo(1f)
    }
    LaunchedEffect(active, enabled, visible) {
        orbit.snapTo(0f)
        if (active && enabled && visible) while (isActive) {
            orbit.animateTo(360f, tween(1800, easing = LinearEasing))
            orbit.snapTo(0f)
        }
    }
    val blue = NowColors.Blue600
    val accent = if (success) Color(0xFF24DFB0) else blue
    Canvas(modifier.fillMaxWidth().height(146.dp).onGloballyPositioned { coordinates ->
        visible = coordinates.boundsInWindow().let { it.width > 0f && it.height > 0f }
    }.semantics {
        contentDescription = if (success) "Verified result" else if (active) "Result pending" else "Result needs attention"
    }) {
        val c = center
        val r = size.height * .32f
        drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = .35f), blue.copy(alpha = .12f), Color.Transparent), c, r * 2.2f), r * 2.2f, c)
        drawCircle(blue.copy(alpha = .23f), r * 1.43f, c, style = Stroke(1.dp.toPx()))
        drawCircle(Brush.sweepGradient(listOf(blue.copy(alpha = .1f), Color(0xFF55E5FF), blue.copy(alpha = .1f)), c), r * 1.16f, c, style = Stroke(2.dp.toPx()))
        if (active) drawArc(
            Color(0xFF69D9FF), orbit.value - 80f, 70f, false,
            Offset(c.x - r * 1.43f, c.y - r * 1.43f),
            androidx.compose.ui.geometry.Size(r * 2.86f, r * 2.86f),
            style = Stroke(3.dp.toPx(), cap = StrokeCap.Round),
        )
        val shield = Path().apply {
            moveTo(c.x, c.y - r * .86f)
            lineTo(c.x + r * .62f, c.y - r * .57f)
            lineTo(c.x + r * .57f, c.y + r * .25f)
            cubicTo(c.x + r * .46f, c.y + r * .60f, c.x + r * .20f, c.y + r * .77f, c.x, c.y + r * .91f)
            cubicTo(c.x - r * .20f, c.y + r * .77f, c.x - r * .46f, c.y + r * .60f, c.x - r * .57f, c.y + r * .25f)
            lineTo(c.x - r * .62f, c.y - r * .57f)
            close()
        }
        drawPath(shield, Brush.linearGradient(listOf(accent.copy(alpha = .95f), Color(0xFF045B7A), Color(0xFF07243D)), Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r)))
        drawPath(shield, Brush.linearGradient(listOf(Color.White.copy(alpha = .8f), accent.copy(alpha = .65f))), style = Stroke(2.dp.toPx()))
        if (success) {
            val start = Offset(c.x - r * .26f, c.y)
            val joint = Offset(c.x - r * .02f, c.y + r * .24f)
            val end = Offset(c.x + r * .34f, c.y - r * .23f)
            val progress = reveal.value
            val check = Path().apply {
                moveTo(start.x, start.y)
                val first = (progress / .4f).coerceIn(0f, 1f)
                lineTo(start.x + (joint.x - start.x) * first, start.y + (joint.y - start.y) * first)
                if (progress > .4f) {
                    val second = ((progress - .4f) / .6f).coerceIn(0f, 1f)
                    lineTo(joint.x + (end.x - joint.x) * second, joint.y + (end.y - joint.y) * second)
                }
            }
            drawPath(check, Color.White, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            if (receiptReveal && progress < 1f) repeat(16) { i ->
                val angle = i * Math.PI / 8 + .24
                val radius = r * (1.15f + progress * .75f + (i % 3) * .12f)
                val pos = Offset(c.x + (cos(angle) * radius).toFloat(), c.y + (sin(angle) * radius).toFloat())
                drawCircle((if (i % 2 == 0) accent else blue).copy(alpha = 1f - progress),
                    (if (i % 3 == 0) 2.2f else 1.2f).dp.toPx(), pos)
            }
        } else if (active) {
            drawCircle(Color.White.copy(alpha = .9f), r * .26f, c, style = Stroke(2.dp.toPx()))
            drawLine(Color.White, c, Offset(c.x, c.y - r * .17f), 2.dp.toPx(), StrokeCap.Round)
            drawLine(Color.White, c, Offset(c.x + r * .14f, c.y + r * .08f), 2.dp.toPx(), StrokeCap.Round)
        } else {
            drawCircle(Color.White.copy(alpha = .85f), 3.dp.toPx(), c)
            drawLine(Color.White.copy(alpha = .85f), Offset(c.x, c.y - r * .36f), Offset(c.x, c.y - r * .1f), 4.dp.toPx(), StrokeCap.Round)
        }
    }
}

@Composable
fun CategoryArtwork(label: String, modifier: Modifier = Modifier) {
    val text = label.lowercase()
    val icon = when { "park" in text -> Icons.Outlined.LocalParking; "charg" in text -> Icons.Outlined.EvStation; "queue" in text -> Icons.Outlined.Groups; "shop" in text || "store" in text -> Icons.Outlined.Storefront; else -> Icons.Outlined.LocationCity }
    val blue = NowColors.Blue600
    Box(modifier.background(Brush.linearGradient(listOf(NowColors.Blue100,NowColors.SurfaceRaised)), NowShapes.medium).border(1.dp,NowColors.InfoBorder,NowShapes.medium),contentAlignment=Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val width = size.width; val height = size.height
            drawOval(blue.copy(alpha=.12f),Offset(width*.1f,height*.68f),androidx.compose.ui.geometry.Size(width*.8f,height*.2f))
            repeat(4) { i -> val x=width*(.08f+i*.23f); val h=height*(.18f+(i%3)*.11f); drawRoundRect(blue.copy(alpha=.12f),Offset(x,height*.72f-h),androidx.compose.ui.geometry.Size(width*.16f,h),androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())) }
        }
        Icon(icon,null,Modifier.size(40.dp),tint=blue)
    }
}

@Composable
fun RefreshContextCard(title: String, subtitle: String, value: String? = null, onClick: (() -> Unit)? = null) {
    NowGlassCard(emphasized = true) {
        Row(Modifier.fillMaxWidth().then(if(onClick != null) Modifier.clickable(onClick=onClick) else Modifier),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
            CategoryArtwork(title, Modifier.size(68.dp))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(title, style=NowType.TitleS,color=NowColors.Ink950)
                Text(subtitle,style=NowType.BodyS,color=NowColors.Ink600)
                value?.let { Text(it,style=NowType.LabelL,color=NowColors.Blue600) }
            }
            if (onClick != null) Icon(Icons.Outlined.ChevronRight,null,tint=NowColors.Ink600)
        }
    }
}

@Composable
fun ProofSteps(photo: Boolean, location: Boolean, answer: String) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        listOf(Triple(Icons.Outlined.PhotoCamera,"Photo",if(photo) "Fresh photo required" else "No photo required"),Triple(Icons.Outlined.LocationOn,"Location",if(location) "Precise proof on site" else "No location required"),Triple(Icons.Outlined.Assignment,"Answer",answer)).forEach { (icon,title,body) ->
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(7.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Surface(shape=CircleShape,color=NowColors.InfoSoft) { Icon(icon,null,Modifier.padding(12.dp).size(22.dp),tint=NowColors.Blue600) }
                Text(title,style=NowType.LabelL,color=NowColors.Ink950)
                Text(body,style=NowType.BodyS,color=NowColors.Ink600,textAlign=TextAlign.Center)
            }
        }
    }
}

@Composable
fun TimelineStep(title: String, detail: String, completed: Boolean, last: Boolean = false) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Column(horizontalAlignment=Alignment.CenterHorizontally) {
            Icon(if(completed) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,null,tint=if(completed) NowColors.LiveDot else NowColors.Ink400,modifier=Modifier.size(24.dp))
            if(!last) Box(Modifier.width(2.dp).height(36.dp).background(if(completed) NowColors.LiveBorder else NowColors.BorderSubtle))
        }
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) { Text(title,style=NowType.LabelL,color=NowColors.Ink950); Text(detail,style=NowType.BodyS,color=NowColors.Ink600) }
    }
}

@Composable
fun ExplorerActions(signature: String) {
    val context=LocalContext.current; val clipboard=LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val valid=signature.length in 64..100 && signature.all { it in "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz" }
    val cluster=BuildConfig.SOLANA_CLUSTER
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        TextButton({ clipboard.setText(AnnotatedString(signature)); copied=true }) { Text(if(copied) "Copied" else "Copy signature") }
        TextButton({
            val suffix=if(cluster=="mainnet-beta") "" else "?cluster=$cluster"
            try { context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://explorer.solana.com/tx/$signature$suffix"))) } catch (_: android.content.ActivityNotFoundException) { clipboard.setText(AnnotatedString(signature)); copied=true }
        },enabled=valid && cluster in setOf("mainnet-beta","devnet","testnet")) { Text("View on Solana ↗") }
    }
}
