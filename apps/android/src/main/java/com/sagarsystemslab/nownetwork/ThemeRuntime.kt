package com.sagarsystemslab.nownetwork

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat

internal object NowThemePreferenceStore {
    private const val PreferencesName = "now_ui_preferences"
    private const val DarkThemeKey = "dark_theme"

    fun readDarkTheme(context: Context): Boolean =
        context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .getBoolean(DarkThemeKey, false)

    fun writeDarkTheme(
        context: Context,
        enabled: Boolean,
    ) {
        context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(DarkThemeKey, enabled)
            .apply()
    }
}

@Composable
internal fun ApplyNowSystemBars(
    activity: ComponentActivity,
    darkTheme: Boolean,
) {
    SideEffect {
        WindowCompat.getInsetsController(
            activity.window,
            activity.window.decorView,
        ).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
}
