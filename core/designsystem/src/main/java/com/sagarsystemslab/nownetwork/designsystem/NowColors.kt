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
    ink950 = Color(0xFF101828),
    ink800 = Color(0xFF1D2939),
    ink700 = Color(0xFF344054),
    ink600 = Color(0xFF475467),
    ink500 = Color(0xFF667085),
    ink400 = Color(0xFF98A2B3),
    ink300 = Color(0xFFD0D5DD),
    ink200 = Color(0xFFEAECF0),
    ink100 = Color(0xFFF2F4F7),
    ink50 = Color(0xFFF8FAFC),
    blue700 = Color(0xFF1747C2),
    blue600 = Color(0xFF1F5EFF),
    blue500 = Color(0xFF3470FF),
    blue100 = Color(0xFFDCE8FF),
    blue50 = Color(0xFFEEF4FF),
    primaryPressed = Color(0xFF1747C2),
    primaryHover = Color(0xFF2457D6),
    liveText = Color(0xFF0A6C5A),
    liveStrong = Color(0xFF0A6C5A),
    liveSoft = Color(0xFFECFDF3),
    liveBorder = Color(0xFFABEFC6),
    liveDot = Color(0xFF12B76A),
    agingText = Color(0xFFA65A00),
    agingStrong = Color(0xFFA65A00),
    agingSoft = Color(0xFFFFF7E6),
    agingBorder = Color(0xFFFEC84B),
    agingDot = Color(0xFFF79009),
    staleText = Color(0xFF667085),
    staleStrong = Color(0xFF475467),
    staleSoft = Color(0xFFF2F4F7),
    staleBorder = Color(0xFFD0D5DD),
    staleDot = Color(0xFF98A2B3),
    conflictText = Color(0xFFB42318),
    conflictStrong = Color(0xFF912018),
    conflictSoft = Color(0xFFFEF3F2),
    conflictBorder = Color(0xFFFECDCA),
    conflictDot = Color(0xFFF04438),
    infoText = Color(0xFF175CD3),
    infoSoft = Color(0xFFEFF8FF),
    infoBorder = Color(0xFFB2DDFF),
    borderSubtle = Color(0xFFEAECF0),
    borderDefault = Color(0xFFD0D5DD),
    borderStrong = Color(0xFF98A2B3),
    surfaceCanvas = Color(0xFFF8FAFC),
    surfacePrimary = Color(0xFFFFFFFF),
    surfaceSecondary = Color(0xFFF2F4F7),
    surfaceRaised = Color(0xFFFFFFFF),
)

internal val NowDarkPalette = NowColorPalette(
    ink950 = Color(0xFFF4F7FC),
    ink800 = Color(0xFFE7EDF6),
    ink700 = Color(0xFFD3DCE8),
    ink600 = Color(0xFFB0BED0),
    ink500 = Color(0xFF8E9CB0),
    ink400 = Color(0xFF6F7E92),
    ink300 = Color(0xFF40516A),
    ink200 = Color(0xFF28364B),
    ink100 = Color(0xFF172033),
    ink50 = Color(0xFF0B1120),
    blue700 = Color(0xFF9CB8FF),
    blue600 = Color(0xFF7397FF),
    blue500 = Color(0xFF86A7FF),
    blue100 = Color(0xFF284477),
    blue50 = Color(0xFF17274B),
    primaryPressed = Color(0xFF89A9FF),
    primaryHover = Color(0xFF7EA0FF),
    liveText = Color(0xFF6FE3B8),
    liveStrong = Color(0xFF8BEBC9),
    liveSoft = Color(0xFF0E2A25),
    liveBorder = Color(0xFF225B4C),
    liveDot = Color(0xFF34D399),
    agingText = Color(0xFFF6C453),
    agingStrong = Color(0xFFFFD978),
    agingSoft = Color(0xFF302610),
    agingBorder = Color(0xFF6A521D),
    agingDot = Color(0xFFFBBF24),
    staleText = Color(0xFFA5B1C2),
    staleStrong = Color(0xFFB9C4D2),
    staleSoft = Color(0xFF182131),
    staleBorder = Color(0xFF34445A),
    staleDot = Color(0xFF8290A4),
    conflictText = Color(0xFFFF9D95),
    conflictStrong = Color(0xFFFFB4AE),
    conflictSoft = Color(0xFF341617),
    conflictBorder = Color(0xFF6A2B2A),
    conflictDot = Color(0xFFF97066),
    infoText = Color(0xFF82B4FF),
    infoSoft = Color(0xFF112541),
    infoBorder = Color(0xFF2A568C),
    borderSubtle = Color(0xFF28364B),
    borderDefault = Color(0xFF40516A),
    borderStrong = Color(0xFF5B6B82),
    surfaceCanvas = Color(0xFF0B1120),
    surfacePrimary = Color(0xFF111827),
    surfaceSecondary = Color(0xFF172033),
    surfaceRaised = Color(0xFF151F31),
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
