package com.sagarsystemslab.nownetwork.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val NowLightColorScheme = lightColorScheme(
    primary = NowLightPalette.blue600,
    onPrimary = NowColors.White,
    primaryContainer = NowLightPalette.blue50,
    onPrimaryContainer = NowLightPalette.blue700,
    secondary = NowLightPalette.ink700,
    onSecondary = NowColors.White,
    secondaryContainer = NowLightPalette.ink100,
    onSecondaryContainer = NowLightPalette.ink800,
    tertiary = NowLightPalette.infoText,
    onTertiary = NowColors.White,
    tertiaryContainer = NowLightPalette.infoSoft,
    onTertiaryContainer = NowLightPalette.infoText,
    error = NowLightPalette.conflictText,
    onError = NowColors.White,
    errorContainer = NowLightPalette.conflictSoft,
    onErrorContainer = NowLightPalette.conflictStrong,
    background = NowLightPalette.surfaceCanvas,
    onBackground = NowLightPalette.ink950,
    surface = NowLightPalette.surfacePrimary,
    onSurface = NowLightPalette.ink950,
    surfaceVariant = NowLightPalette.surfaceSecondary,
    onSurfaceVariant = NowLightPalette.ink600,
    outline = NowLightPalette.borderDefault,
    outlineVariant = NowLightPalette.borderSubtle,
    scrim = Color(0x66101828),
)

private val NowDarkColorScheme = darkColorScheme(
    primary = NowDarkPalette.blue600,
    onPrimary = Color(0xFF071225),
    primaryContainer = NowDarkPalette.blue50,
    onPrimaryContainer = NowDarkPalette.blue700,
    secondary = NowDarkPalette.ink600,
    onSecondary = Color(0xFF071225),
    secondaryContainer = NowDarkPalette.ink100,
    onSecondaryContainer = NowDarkPalette.ink800,
    tertiary = NowDarkPalette.infoText,
    onTertiary = Color(0xFF071225),
    tertiaryContainer = NowDarkPalette.infoSoft,
    onTertiaryContainer = NowDarkPalette.infoText,
    error = NowDarkPalette.conflictText,
    onError = Color(0xFF2A0908),
    errorContainer = NowDarkPalette.conflictSoft,
    onErrorContainer = NowDarkPalette.conflictStrong,
    background = NowDarkPalette.surfaceCanvas,
    onBackground = NowDarkPalette.ink950,
    surface = NowDarkPalette.surfacePrimary,
    onSurface = NowDarkPalette.ink950,
    surfaceVariant = NowDarkPalette.surfaceSecondary,
    onSurfaceVariant = NowDarkPalette.ink600,
    outline = NowDarkPalette.borderDefault,
    outlineVariant = NowDarkPalette.borderSubtle,
    scrim = Color(0x99000000),
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
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) NowDarkPalette else NowLightPalette
    val materialColorScheme = if (darkTheme) NowDarkColorScheme else NowLightColorScheme

    CompositionLocalProvider(
        LocalNowColorPalette provides palette,
    ) {
        MaterialTheme(
            colorScheme = materialColorScheme,
            typography = NowTypography,
            shapes = NowShapes,
            content = content,
        )
    }
}
