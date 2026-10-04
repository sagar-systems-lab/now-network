package com.sagarsystemslab.nownetwork.feature.ask

import com.sagarsystemslab.nownetwork.experience.ExperienceRepository
import com.sagarsystemslab.nownetwork.model.GeoCenter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.*

@Singleton
class AskRepository @Inject constructor(private val experience: ExperienceRepository) {
    suspend fun resolve(payload: JsonObject, key: String): JsonObject =
        experience.mutate("/v1/asks/resolve", payload, "POST", key)

    suspend fun coverage(target: GeoCenter, origin: JsonObject): JsonObject = experience.get("/v1/coverage", mapOf(
        "lat" to target.latitude.toString(), "lng" to target.longitude.toString(),
        "requester_lat" to origin.getValue("lat").jsonPrimitive.content,
        "requester_lng" to origin.getValue("lng").jsonPrimitive.content,
        "requester_accuracy_m" to origin.getValue("accuracy_m").jsonPrimitive.content,
        "requester_captured_at" to origin.getValue("captured_at").jsonPrimitive.content,
    ))

    suspend fun available(position: JsonObject): JsonObject = experience.mutate("/v1/me/contributor-presence", position, "PUT")
    suspend fun unavailable() { experience.mutate("/v1/me/contributor-presence", buildJsonObject {}, "DELETE") }
}
