package com.sagarsystemslab.nownetwork.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
internal data class NowColorPalette(
    val ink950: Color,
    val ink800: Color,
    val ink700: Color,
    val ink600: Color,
    val ink500: Color,
    val ink400: Color,
    val ink300: Color,
    val ink200: Color,
    val ink100: Color,
    val ink50: Color,
    val blue700: Color,
    val blue600: Color,
    val blue500: Color,
    val blue100: Color,
    val blue50: Color,
    val primaryPressed: Color,
    val primaryHover: Color,
    val liveText: Color,
    val liveStrong: Color,
    val liveSoft: Color,
    val liveBorder: Color,
    val liveDot: Color,
    val agingText: Color,
    val agingStrong: Color,
    val agingSoft: Color,
    val agingBorder: Color,
    val agingDot: Color,
    val staleText: Color,
    val staleStrong: Color,
    val staleSoft: Color,
    val staleBorder: Color,
    val staleDot: Color,
    val conflictText: Color,
    val conflictStrong: Color,
    val conflictSoft: Color,
    val conflictBorder: Color,
    val conflictDot: Color,
    val infoText: Color,
    val infoSoft: Color,
    val infoBorder: Color,
    val borderSubtle: Color,
    val borderDefault: Color,
    val borderStrong: Color,
    val surfaceCanvas: Color,
    val surfacePrimary: Color,
    val surfaceSecondary: Color,
    val surfaceRaised: Color,
)

internal val NowLightPalette = NowColorPalette(
    ink950 = Color(0xFF081429),
    ink800 = Color(0xFF172F50),
    ink700 = Color(0xFF294464),
    ink600 = Color(0xFF4C607B),
    ink500 = Color(0xFF60728B),
    ink400 = Color(0xFF6C809B),
    ink300 = Color(0xFFB3CBE5),
    ink200 = Color(0xFFD5E5F5),
    ink100 = Color(0xFFEDF5FF),
    ink50 = Color(0xFFF4F9FF),
    blue700 = Color(0xFF075AC9),
    blue600 = Color(0xFF075CE5),
    blue500 = Color(0xFF0070CE),
    blue100 = Color(0xFFC8DFFB),
    blue50 = Color(0xFFE4F0FF),
    primaryPressed = Color(0xFF064CAC),
    primaryHover = Color(0xFF0868E5),
    liveText = Color(0xFF006E4F),
    liveStrong = Color(0xFF00664A),
    liveSoft = Color(0xFFE7F9F1),
    liveBorder = Color(0xFFA1DECA),
    liveDot = Color(0xFF008D63),
    agingText = Color(0xFF845800),
    agingStrong = Color(0xFF754D00),
    agingSoft = Color(0xFFFFF7DF),
    agingBorder = Color(0xFFE7CF89),
    agingDot = Color(0xFFA66A00),
    staleText = Color(0xFFB11E44),
    staleStrong = Color(0xFF9B193A),
    staleSoft = Color(0xFFFFF0F3),
    staleBorder = Color(0xFFE9B4C1),
    staleDot = Color(0xFFBF3455),
    conflictText = Color(0xFF845800),
    conflictStrong = Color(0xFF754D00),
    conflictSoft = Color(0xFFFFF7DF),
    conflictBorder = Color(0xFFE7CF89),
    conflictDot = Color(0xFFA66A00),
    infoText = Color(0xFF0755A8),
    infoSoft = Color(0xFFEAF4FF),
    infoBorder = Color(0xFFB5D6F5),
    borderSubtle = Color(0xFFD5E5F5),
    borderDefault = Color(0xFFB3CBE5),
    borderStrong = Color(0xFF6C809B),
    surfaceCanvas = Color(0xFFF4F9FF),
    surfacePrimary = Color(0xFFFFFFFF),
    surfaceSecondary = Color(0xFFEDF5FF),
    surfaceRaised = Color(0xFFEDF5FF),
)

internal val NowDarkPalette = NowColorPalette(
    ink950 = Color(0xFFF5F8FF),
    ink800 = Color(0xFFE6F0FF),
    ink700 = Color(0xFFC4D8F2),
    ink600 = Color(0xFFA6BFDF),
    ink500 = Color(0xFF879FBD),
    ink400 = Color(0xFF7792B4),
    ink300 = Color(0xFF395D85),
    ink200 = Color(0xFF224565),
    ink100 = Color(0xFF0B2445),
    ink50 = Color(0xFF030C1C),
    blue700 = Color(0xFF9DDFFF),
    blue600 = Color(0xFF69BDFF),
    blue500 = Color(0xFF4DA2FF),
    blue100 = Color(0xFF174D86),
    blue50 = Color(0xFF092F6C),
    primaryPressed = Color(0xFF155FBF),
    primaryHover = Color(0xFF247EEB),
    liveText = Color(0xFF52E2B1),
    liveStrong = Color(0xFF8DF3D0),
    liveSoft = Color(0xFF092C2D),
    liveBorder = Color(0xFF166455),
    liveDot = Color(0xFF28D9A0),
    agingText = Color(0xFFFFD35B),
    agingStrong = Color(0xFFFFE08F),
    agingSoft = Color(0xFF2B291D),
    agingBorder = Color(0xFF69562C),
    agingDot = Color(0xFFFFCC47),
    staleText = Color(0xFFFF91A5),
    staleStrong = Color(0xFFFFB1BF),
    staleSoft = Color(0xFF2B1B30),
    staleBorder = Color(0xFF72334D),
    staleDot = Color(0xFFFF6E8C),
    conflictText = Color(0xFFFFD35B),
    conflictStrong = Color(0xFFFFE08F),
    conflictSoft = Color(0xFF2B291D),
    conflictBorder = Color(0xFF69562C),
    conflictDot = Color(0xFFFFCC47),
    infoText = Color(0xFF87CCFF),
    infoSoft = Color(0xFF0B2445),
    infoBorder = Color(0xFF224E79),
    borderSubtle = Color(0xFF224565),
    borderDefault = Color(0xFF31577E),
    borderStrong = Color(0xFF699DC8),
    surfaceCanvas = Color(0xFF030C1C),
    surfacePrimary = Color(0xFF081A32),
    surfaceSecondary = Color(0xFF0B2445),
    surfaceRaised = Color(0xFF0B2445),
)

internal val LocalNowColorPalette = staticCompositionLocalOf { NowLightPalette }

object NowColors {
    val White = Color(0xFFFFFFFF)

    val Ink950: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.ink950
    val Ink800: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.ink800
    val Ink700: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.ink700
    val Ink600: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.ink600
    val Ink500: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.ink500
    val Ink400: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.ink400
    val Ink300: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.ink300
    val Ink200: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.ink200
    val Ink100: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.ink100
    val Ink50: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.ink50

    val Blue700: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.blue700
    val Blue600: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.blue600
    val Blue500: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.blue500
    val Blue100: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.blue100
    val Blue50: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.blue50

    val Primary: Color
        @Composable @ReadOnlyComposable get() = Blue600
    val PrimaryPressed: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.primaryPressed
    val PrimaryHover: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.primaryHover
    val PrimarySoft: Color
        @Composable @ReadOnlyComposable get() = Blue50

    val LiveText: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.liveText
    val LiveStrong: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.liveStrong
    val LiveSoft: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.liveSoft
    val LiveBorder: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.liveBorder
    val LiveDot: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.liveDot

    val AgingText: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.agingText
    val AgingStrong: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.agingStrong
    val AgingSoft: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.agingSoft
    val AgingBorder: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.agingBorder
    val AgingDot: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.agingDot

    val StaleText: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.staleText
    val StaleStrong: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.staleStrong
    val StaleSoft: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.staleSoft
    val StaleBorder: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.staleBorder
    val StaleDot: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.staleDot

    val ConflictText: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.conflictText
    val ConflictStrong: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.conflictStrong
    val ConflictSoft: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.conflictSoft
    val ConflictBorder: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.conflictBorder
    val ConflictDot: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.conflictDot

    val InfoText: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.infoText
    val InfoSoft: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.infoSoft
    val InfoBorder: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.infoBorder

    val BorderSubtle: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.borderSubtle
    val BorderDefault: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.borderDefault
    val BorderStrong: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.borderStrong

    val SurfaceCanvas: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.surfaceCanvas
    val SurfacePrimary: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.surfacePrimary
    val SurfaceSecondary: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.surfaceSecondary
    val SurfaceRaised: Color
        @Composable @ReadOnlyComposable get() = LocalNowColorPalette.current.surfaceRaised
}
