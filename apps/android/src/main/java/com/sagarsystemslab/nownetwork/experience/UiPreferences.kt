package com.sagarsystemslab.nownetwork.experience

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.map

private val Context.nowUiDataStore by preferencesDataStore("now_experience_ui")
val LocalDistanceUnit = androidx.compose.runtime.staticCompositionLocalOf { "metric" }

@androidx.compose.runtime.Composable
fun displayDistance(meters: Double?): String {
    if (meters == null || !meters.isFinite()) return "Distance unavailable"
    return if (LocalDistanceUnit.current == "imperial") {
        val miles = meters / 1609.344
        if (miles < 0.1) "${(meters * 3.28084).toInt()} ft" else String.format(java.util.Locale.getDefault(), "%.1f mi", miles)
    } else if (meters < 1000) "${meters.toInt()} m" else String.format(java.util.Locale.getDefault(), "%.1f km", meters / 1000)
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }
data class UiPreferences(val theme: ThemeMode = ThemeMode.SYSTEM, val reduceMotion: Boolean = false, val distanceUnit: String = "metric", val locale: String = "en")

@Singleton
class UiPreferencesStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val theme = stringPreferencesKey("theme_mode")
    private val motion = booleanPreferencesKey("reduce_motion")
    private val units = stringPreferencesKey("distance_unit")
    private val locale = stringPreferencesKey("locale")
    private val migrated = booleanPreferencesKey("legacy_theme_migrated")
    private val legacy = context.getSharedPreferences("now_ui_preferences", Context.MODE_PRIVATE)
    val initial = UiPreferences(theme = if (legacy.contains("dark_theme")) {
        if (legacy.getBoolean("dark_theme", false)) ThemeMode.DARK else ThemeMode.LIGHT
    } else ThemeMode.SYSTEM)
    val state = context.nowUiDataStore.data.map { prefs ->
        UiPreferences(theme = prefs[theme]?.let { value -> ThemeMode.entries.firstOrNull { it.name == value } } ?: initial.theme,
            reduceMotion = prefs[motion] ?: false, distanceUnit = prefs[units] ?: "metric", locale = prefs[locale] ?: "en")
    }
    suspend fun migrate() {
        context.nowUiDataStore.edit { prefs ->
            if (prefs[migrated] != true) {
                if (prefs[theme] == null) prefs[theme] = initial.theme.name
                prefs[migrated] = true
            }
        }
    }
    suspend fun theme(mode: ThemeMode) { context.nowUiDataStore.edit { it[theme] = mode.name } }
    suspend fun motion(reduce: Boolean) { context.nowUiDataStore.edit { it[motion] = reduce } }
    suspend fun units(value: String) { require(value in listOf("metric", "imperial")); context.nowUiDataStore.edit { it[units] = value } }
}
