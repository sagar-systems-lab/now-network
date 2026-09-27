package com.sagarsystemslab.nownetwork.config

data class SolanaRuntimeConfig(
    val cluster: String,
    val rpcUrl: String,
    val programId: String,
    val walletIdentityUri: String,
    val walletIconUri: String,
) {
    val configured: Boolean
        get() =
            cluster.isNotBlank() &&
                rpcUrl.startsWith("https://") &&
                programId.isNotBlank() &&
                walletIdentityUri.startsWith("https://")
}
