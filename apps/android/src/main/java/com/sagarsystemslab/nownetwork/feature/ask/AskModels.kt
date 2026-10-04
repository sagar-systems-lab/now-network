package com.sagarsystemslab.nownetwork.feature.ask

import com.sagarsystemslab.nownetwork.model.GeoCenter
import java.util.UUID
import kotlinx.serialization.json.*

enum class AskStage { TARGET, NEED, PREVIEW }
enum class AskNeed(val key: String, val locationType: String, val title: String, val question: String) {
    PARKING("parking.available_spaces.v1", "PARKING", "Parking availability", "How many parking spaces are available?"),
    GATE("gate.open_closed.v1", "GATE", "Gate access", "Is this gate open or closed?"),
    VISUAL("visual.current_condition.v1", "PLACE", "Current condition", "What does this place look like now?"),
}

data class AskDraft(
    val name: String = "",
    val target: GeoCenter? = null,
    val need: AskNeed? = null,
    val key: String = UUID.randomUUID().toString(),
) {
    val targetReady: Boolean get() = name.trim().length in 1..120 && target?.valid == true
    fun payload(): JsonObject {
        check(targetReady && need != null)
        return buildJsonObject {
            put("location", buildJsonObject {
                put("name", name.trim()); put("lat", requireNotNull(target).latitude)
                put("lng", target.longitude); put("location_type", need.locationType)
            })
            put("policy_template_key", need.key)
        }
    }
}

data class AskUiState(
    val draft: AskDraft = AskDraft(),
    val stage: AskStage = AskStage.TARGET,
    val busy: Boolean = false,
    val checking: Boolean = false,
    val coverage: JsonObject? = null,
    val notice: String? = null,
    val resolvedStateId: String? = null,
    val activeRefresh: Boolean = false,
)
