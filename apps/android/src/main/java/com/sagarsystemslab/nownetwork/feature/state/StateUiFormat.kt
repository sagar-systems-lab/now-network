package com.sagarsystemslab.nownetwork.feature.state

import com.sagarsystemslab.nownetwork.model.StateDetail
import com.sagarsystemslab.nownetwork.model.StateSummary
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

enum class FreshnessKind {
    LIVE,
    AGING,
    STALE,
    CONFLICT,
    UNKNOWN,
}

fun StateSummary.freshnessAt(nowMillis: Long): FreshnessKind =
    deriveFreshness(
        conflictActive = conflictActive,
        reported = freshnessStatus,
        agingAtMillis = agingAtMillis,
        freshUntilMillis = freshUntilMillis,
        nowMillis = nowMillis,
    )

fun StateDetail.freshnessAt(nowMillis: Long): FreshnessKind =
    deriveFreshness(
        conflictActive = conflictActive,
        reported = freshnessStatus,
        agingAtMillis = agingAtMillis,
        freshUntilMillis = freshUntilMillis,
        nowMillis = nowMillis,
    )

private fun deriveFreshness(
    conflictActive: Boolean,
    reported: String?,
    agingAtMillis: Long?,
    freshUntilMillis: Long?,
    nowMillis: Long,
): FreshnessKind {
    if (conflictActive) return FreshnessKind.CONFLICT

    if (agingAtMillis != null && freshUntilMillis != null) {
        return when {
            nowMillis < agingAtMillis -> FreshnessKind.LIVE
            nowMillis < freshUntilMillis -> FreshnessKind.AGING
            else -> FreshnessKind.STALE
        }
    }

    return when (reported) {
        "LIVE" -> FreshnessKind.LIVE
        "AGING" -> FreshnessKind.AGING
        "STALE" -> FreshnessKind.STALE
        else -> FreshnessKind.UNKNOWN
    }
}

fun relativeObservedTime(
    observedAtMillis: Long?,
    nowMillis: Long,
): String {
    if (observedAtMillis == null) return "verification time unavailable"

    val elapsedSeconds = ((nowMillis - observedAtMillis).coerceAtLeast(0L)) / 1_000L
    return when {
        elapsedSeconds < 5L -> "verified just now"
        elapsedSeconds < 60L -> "verified ${elapsedSeconds}s ago"
        elapsedSeconds < 3_600L -> "verified ${elapsedSeconds / 60L}m ago"
        elapsedSeconds < 86_400L -> "verified ${elapsedSeconds / 3_600L}h ago"
        else -> "verified ${elapsedSeconds / 86_400L}d ago"
    }
}

fun formatStateValue(
    valueJson: String?,
    unitCode: String?,
): String {
    if (valueJson.isNullOrBlank()) return "No current value"

    val rendered = runCatching {
        when (val value = Json.parseToJsonElement(valueJson)) {
            is JsonPrimitive -> value.contentOrNull ?: value.toString()
            is JsonObject -> if ((value["kind"] as? JsonPrimitive)?.contentOrNull == "visual") "Visual proof received" else scaledNumericValue(value)
                ?: primitiveObjectValue(value)
                ?: "Verified observation"
            else -> "Verified observation"
        }
    }.getOrElse {
        if (valueJson.trimStart().startsWith("{") || valueJson.trimStart().startsWith("[")) "Observation unavailable" else valueJson
    }

    return if (unitCode.isNullOrBlank() || rendered.endsWith(" $unitCode")) {
        rendered
    } else {
        "$rendered $unitCode"
    }
}

private fun scaledNumericValue(value: JsonObject): String? {
    val scaled = value["scaled_value"] as? JsonPrimitive ?: return null
    val scale = (value["scale"] as? JsonPrimitive)?.intOrNull ?: return null
    if (scale !in 0..18) return null

    return runCatching {
        BigDecimal(scaled.content)
            .movePointLeft(scale)
            .stripTrailingZeros()
            .toPlainString()
    }.getOrNull()
}

private fun primitiveObjectValue(value: JsonObject): String? {
    val primitive = value["value"] as? JsonPrimitive ?: return null
    return primitive.contentOrNull
}
