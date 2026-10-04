package com.sagarsystemslab.nownetwork.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp

/** Shared luminous surface. Its content stays native, selectable and accessible. */
@Composable
fun NowGlassCard(
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    contentPadding: Dp = 12.dp,
    spacing: Dp = 10.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background.red < .2f
    val colors = if (dark) {
        if (emphasized) listOf(Color(0xFF0C2B55), Color(0xFF071A34), Color(0xFF041225))
        else listOf(Color(0xFF0B203B), Color(0xFF07162B), Color(0xFF051224))
    } else listOf(Color.White, Color(0xFFFAFDFF), Color(0xFFF0F7FF))
    val edge = Brush.linearGradient(if (dark)
        listOf(Color(0xFF429CFF).copy(alpha = if (emphasized) .8f else .35f), Color(0xFF0B284D), Color(0xFF2470AB).copy(alpha = .4f))
        else listOf(Color.White, Color(0xFFE3EDFA), Color(0xFFCDDEEE)))
    Surface(
        modifier = modifier.fillMaxWidth().shadow(if (emphasized) 10.dp else 5.dp, NowShapes.large,
            ambientColor = Color(0xFF0769DC), spotColor = Color(0xFF0769DC)),
        shape = NowShapes.large,
        color = NowColors.SurfacePrimary,
        border = BorderStroke(1.dp, edge),
    ) {
        Column(
            modifier = Modifier.background(Brush.linearGradient(colors)).drawWithCache {
                val bloom = Brush.radialGradient(listOf(Color(0xFF278AFF).copy(alpha = if (dark) .12f else .055f), Color.Transparent), Offset(size.width * .25f, 0f), size.width * .8f)
                onDrawBehind { drawRect(bloom) }
            }.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
    }
}

@Composable
fun NowSectionTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = NowType.TitleM, color = NowColors.Ink950)
        subtitle?.let { Text(it, style = NowType.BodyS, color = NowColors.Ink600) }
    }
}

@Composable
fun nowActionBrush(): Brush = Brush.horizontalGradient(
    if (MaterialTheme.colorScheme.background.red < .2f)
        listOf(Color(0xFF0871EB), Color(0xFF0751F5), Color(0xFF007ACE))
    else listOf(Color(0xFF0878EE), Color(0xFF0860EA), Color(0xFF007ABF)),
)

@Composable
fun Modifier.nowPageBackground(): Modifier {
    val dark = MaterialTheme.colorScheme.background.red < .2f
    val canvas = NowColors.SurfaceCanvas
    return drawWithCache {
        val light = Brush.radialGradient(listOf(Color(0xFF2079F5).copy(alpha = if (dark) .14f else .06f), Color.Transparent),
            Offset(size.width * .72f, size.height * .12f), size.width * 1.1f)
        onDrawBehind { drawRect(canvas); drawRect(light) }
    }
}
