package com.sagarsystemslab.nownetwork.feature.earn

import com.sagarsystemslab.nownetwork.config.RewardDisplayConfig
import java.math.BigDecimal
import java.math.BigInteger

fun formatReward(
    atomic: String,
    mint: String,
    config: RewardDisplayConfig,
): String {
    if (!config.valid || !config.matches(mint)) {
        return "${atomic} atomic units"
    }

    return runCatching {
        val amount = BigDecimal(BigInteger(atomic), config.decimals)
            .stripTrailingZeros()
            .toPlainString()
        "${amount} ${config.symbol}"
    }.getOrElse {
        "${atomic} atomic units"
    }
}

fun formatOpportunityDistance(distanceMeters: Double?): String =
    when {
        distanceMeters == null -> "Distance unavailable offline"
        distanceMeters < 1_000.0 -> "${distanceMeters.toInt()} m away"
        else -> String.format("%.1f km away", distanceMeters / 1_000.0)
    }

fun formatOpportunityTime(
    expiresAtMillis: Long,
    nowMillis: Long,
): String {
    val remainingSeconds = ((expiresAtMillis - nowMillis).coerceAtLeast(0L)) / 1_000L

    return when {
        remainingSeconds == 0L -> "Expired"
        remainingSeconds < 60L -> "${remainingSeconds}s left"
        remainingSeconds < 3_600L -> "${remainingSeconds / 60L}m left"
        else -> "${remainingSeconds / 3_600L}h left"
    }
}
