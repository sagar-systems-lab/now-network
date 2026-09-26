package com.sagarsystemslab.nownetwork.config

data class PublicRuntimeConfig(
    val apiBaseUrl: String,
    val supabaseUrl: String,
    val supabasePublishableKey: String,
    val solanaCluster: String,
) {
    val apiConfigured: Boolean
        get() = apiBaseUrl.isNotBlank()

    val authConfigured: Boolean
        get() = supabaseUrl.isNotBlank() && supabasePublishableKey.isNotBlank()
}
