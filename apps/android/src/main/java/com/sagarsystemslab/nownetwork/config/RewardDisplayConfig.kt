package com.sagarsystemslab.nownetwork.config

data class RewardDisplayConfig(
    val mint: String,
    val symbol: String,
    val decimals: Int,
) {
    val valid: Boolean
        get() = symbol.isNotBlank() && decimals in 0..18

    fun matches(rewardMint: String): Boolean =
        mint.isNotBlank() && mint == rewardMint
}
