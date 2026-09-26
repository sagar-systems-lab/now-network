package com.sagarsystemslab.nownetwork.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val NowLightColorScheme = lightColorScheme(
    primary = NowColors.Blue600,
    onPrimary = NowColors.White,
    primaryContainer = NowColors.Blue50,
    onPrimaryContainer = NowColors.Blue700,
    secondary = NowColors.Ink700,
    onSecondary = NowColors.White,
    secondaryContainer = NowColors.Ink100,
    onSecondaryContainer = NowColors.Ink800,
    error = NowColors.ConflictText,
    onError = NowColors.White,
    errorContainer = NowColors.ConflictSoft,
    onErrorContainer = NowColors.ConflictStrong,
    background = NowColors.SurfaceCanvas,
    onBackground = NowColors.Ink950,
    surface = NowColors.SurfacePrimary,
    onSurface = NowColors.Ink950,
    surfaceVariant = NowColors.SurfaceSecondary,
    onSurfaceVariant = NowColors.Ink600,
    outline = NowColors.BorderDefault,
    outlineVariant = NowColors.BorderSubtle,
    scrim = Color(0x66101828),
)

val NowShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

@Composable
fun NowTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = NowLightColorScheme,
        typography = NowTypography,
        shapes = NowShapes,
        content = content,
    )
}
