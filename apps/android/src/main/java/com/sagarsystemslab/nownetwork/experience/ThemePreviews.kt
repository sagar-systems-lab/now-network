package com.sagarsystemslab.nownetwork.experience

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sagarsystemslab.nownetwork.designsystem.*

@Composable
internal fun ThemePreviews(current: ThemeMode, select: (ThemeMode) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SYSTEM).forEach { mode ->
            val active = mode == current
            Surface(onClick = { select(mode) }, modifier = Modifier.weight(1f).semantics { selected = active },
                shape = NowShapes.medium, color = NowColors.SurfacePrimary,
                border = BorderStroke(if (active) 2.dp else 1.dp, if (active) NowColors.Blue600 else NowColors.BorderSubtle)) {
                Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Canvas(Modifier.fillMaxWidth().height(60.dp)) {
                        val background = when (mode) {
                            ThemeMode.DARK -> listOf(Color(0xFF061426), Color(0xFF0C2445))
                            ThemeMode.LIGHT -> listOf(Color(0xFFF0F7FF), Color.White)
                            ThemeMode.SYSTEM -> listOf(Color(0xFFF0F7FF), Color(0xFF0B213F))
                        }
                        drawRoundRect(Brush.horizontalGradient(background), cornerRadius = CornerRadius(6.dp.toPx()))
                        drawRoundRect(Color(0xFF389CFF), Offset(size.width * .10f, size.height * .12f), Size(size.width * .40f, size.height * .07f), CornerRadius(2.dp.toPx()))
                        drawRoundRect(Color(0xFF1680EF).copy(alpha = .28f), Offset(size.width * .10f, size.height * .29f), Size(size.width * .80f, size.height * .35f), CornerRadius(4.dp.toPx()))
                        repeat(2) { index -> drawRoundRect(Color(0xFF4C9DDC).copy(alpha = .30f), Offset(size.width * .10f, size.height * (.74f + index * .12f)), Size(size.width * .65f, size.height * .05f), CornerRadius(2.dp.toPx())) }
                    }
                    Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }, style = NowType.LabelM,
                        color = if (active) NowColors.Blue600 else NowColors.Ink700)
                }
            }
        }
    }
}
