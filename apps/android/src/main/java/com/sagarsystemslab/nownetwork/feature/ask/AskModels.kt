package com.sagarsystemslab.nownetwork.feature.ask

import com.sagarsystemslab.nownetwork.model.GeoCenter
import java.util.UUID
import kotlinx.serialization.json.*

enum class AskStage { TARGET, NEED, PREVIEW }
enum class AskNeed(val key: String, val locationType: String, val title: String, val question: String) {
    PARKING("parking.available_spaces.v1", "PARKING", "Parking availability", "How many parking spaces are available?"),
    GATE("gate.open_closed.v1", "GATE", "Gate access", "Is this gate open or closed?"),
    VISUAL("visual.current_condition.v1", "PLACE", "Current condition", "What does this place look like now?"),
    OTHER("visual.current_condition.v1", "PLACE", "Ask your own question", "Type exactly what you want someone there to verify"),
}

data class AskDraft(
    val name: String = "",
    val target: GeoCenter? = null,
    val need: AskNeed? = null,
    val key: String = UUID.randomUUID().toString(),
    val customQuestion: String = "",
) {
    val targetReady: Boolean get() = name.trim().length in 1..120 && target?.valid == true
    val question: String get() = if (need == AskNeed.OTHER) customQuestion.trim() else need?.question.orEmpty()
    val needReady: Boolean get() = need != null && (need != AskNeed.OTHER || question.length in 8..200)
    fun searching(): AskDraft = copy(name = "", target = null)
    fun payload(): JsonObject {
        check(targetReady && needReady)
        val selectedTarget = requireNotNull(target)
        val selectedNeed = requireNotNull(need)
        return buildJsonObject {
            put("location", buildJsonObject {
                put("name", name.trim()); put("lat", selectedTarget.latitude)
                put("lng", selectedTarget.longitude); put("location_type", selectedNeed.locationType)
            })
            put("policy_template_key", selectedNeed.key)
            if (selectedNeed == AskNeed.OTHER) put("custom_question", question)
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
    val query: String = draft.name,
    val mapCenter: GeoCenter? = draft.target,
    val suggestions: List<PlaceSuggestion> = emptyList(),
    val searching: Boolean = false,
    val searchMessage: String? = null,
)

data class PlaceSuggestion(val name: String, val address: String, val center: GeoCenter)
