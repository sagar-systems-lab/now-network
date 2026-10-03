package com.sagarsystemslab.nownetwork.experience

import android.content.Context
import com.sagarsystemslab.nownetwork.config.BrowseAreaConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class BrowseContextStore @Inject constructor(@ApplicationContext context: Context, fallback: BrowseAreaConfig) {
    private val preferences = context.getSharedPreferences("now_browse_context", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(
        BrowseAreaConfig(preferences.getString("label", null) ?: fallback.label,
            preferences.getString("latitude", null)?.toDoubleOrNull() ?: fallback.latitude,
            preferences.getString("longitude", null)?.toDoubleOrNull() ?: fallback.longitude,
            preferences.getInt("radius", fallback.radiusMeters)),
    )
    val state = mutable.asStateFlow()
    private fun snapshotKey(kind: String, actor: String?, area: BrowseAreaConfig): String {
        val identity = "$kind|${actor ?: "public"}|${area.latitude}|${area.longitude}|${area.radiusMeters}"
        return "snapshot_" + java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    fun snapshot(kind: String, actor: String? = null, area: BrowseAreaConfig = state.value): Set<String> =
        preferences.getStringSet(snapshotKey(kind, actor, area), emptySet()).orEmpty().toSet()
    fun saveSnapshot(kind: String, ids: Set<String>, actor: String? = null, area: BrowseAreaConfig = state.value) {
        preferences.edit().putStringSet(snapshotKey(kind, actor, area), ids).apply()
    }
    fun select(label: String, latitude: Double, longitude: Double, radius: Int = 3000) {
        val area = BrowseAreaConfig(label, latitude, longitude, radius)
        require(area.configured)
        preferences.edit().putString("label", label).putString("latitude", latitude.toString())
            .putString("longitude", longitude.toString()).putInt("radius", radius).apply()
        mutable.value = area
    }
}
