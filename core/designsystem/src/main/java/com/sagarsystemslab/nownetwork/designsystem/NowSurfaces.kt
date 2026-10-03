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
import androidx.compose.ui.unit.dp

/** Shared luminous surface. Its content stays native, selectable and accessible. */
@Composable
fun NowGlassCard(
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = if (emphasized) listOf(NowColors.InfoSoft, NowColors.SurfacePrimary)
        else listOf(NowColors.SurfaceRaised.copy(alpha = .5f), NowColors.SurfacePrimary)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = NowShapes.large,
        color = NowColors.SurfacePrimary,
        border = BorderStroke(1.dp, if (emphasized) NowColors.InfoBorder else NowColors.BorderSubtle),
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.background(Brush.linearGradient(colors)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
        listOf(Color(0xFF145AD5), Color(0xFF2449E8), Color(0xFF0071BA))
    else listOf(Color(0xFF0070CE), Color(0xFF075CE5), Color(0xFF0872B0)),
)
