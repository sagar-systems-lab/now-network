package com.sagarsystemslab.nownetwork.designsystem

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

class NowContrastTest {
    @Test
    fun semanticTextPairsMeetWcagAaContrast() {
        val pairs = listOf(
            "light primary text" to (NowLightPalette.ink950 to NowLightPalette.surfacePrimary),
            "light body text" to (NowLightPalette.ink600 to NowLightPalette.surfacePrimary),
            "light secondary text" to (NowLightPalette.ink500 to NowLightPalette.surfacePrimary),
            "light live" to (NowLightPalette.liveText to NowLightPalette.liveSoft),
            "light aging" to (NowLightPalette.agingText to NowLightPalette.agingSoft),
            "light stale" to (NowLightPalette.staleText to NowLightPalette.staleSoft),
            "light conflict" to (NowLightPalette.conflictText to NowLightPalette.conflictSoft),
            "light info" to (NowLightPalette.infoText to NowLightPalette.infoSoft),
            "dark primary text" to (NowDarkPalette.ink950 to NowDarkPalette.surfacePrimary),
            "dark body text" to (NowDarkPalette.ink600 to NowDarkPalette.surfacePrimary),
            "dark secondary text" to (NowDarkPalette.ink500 to NowDarkPalette.surfacePrimary),
            "dark live" to (NowDarkPalette.liveText to NowDarkPalette.liveSoft),
            "dark aging" to (NowDarkPalette.agingText to NowDarkPalette.agingSoft),
            "dark stale" to (NowDarkPalette.staleText to NowDarkPalette.staleSoft),
            "dark conflict" to (NowDarkPalette.conflictText to NowDarkPalette.conflictSoft),
            "dark info" to (NowDarkPalette.infoText to NowDarkPalette.infoSoft),
        )

        pairs.forEach { (name, colors) ->
            val ratio = contrastRatio(
                foreground = colors.first,
                background = colors.second,
            )
            assertTrue(
                "$name contrast was $ratio",
                ratio >= 4.5,
            )
        }
    }

    private fun contrastRatio(
        foreground: Color,
        background: Color,
    ): Double {
        val foregroundLuminance = relativeLuminance(foreground)
        val backgroundLuminance = relativeLuminance(background)
        val lighter = maxOf(foregroundLuminance, backgroundLuminance)
        val darker = minOf(foregroundLuminance, backgroundLuminance)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(color: Color): Double =
        0.2126 * linearize(color.red.toDouble()) +
            0.7152 * linearize(color.green.toDouble()) +
            0.0722 * linearize(color.blue.toDouble())

    private fun linearize(channel: Double): Double =
        if (channel <= 0.04045) {
            channel / 12.92
        } else {
            Math.pow((channel + 0.055) / 1.055, 2.4)
        }
}
