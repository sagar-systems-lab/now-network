package com.sagarsystemslab.nownetwork.auth

import com.sagarsystemslab.nownetwork.config.PublicRuntimeConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.realtime.Realtime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SupabaseRuntimeClient @Inject constructor(
    private val config: PublicRuntimeConfig,
) {
    val client: SupabaseClient? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        if (!config.authConfigured) {
            null
        } else {
            createSupabaseClient(
                supabaseUrl = config.supabaseUrl,
                supabaseKey = config.supabasePublishableKey,
            ) {
                install(Auth) {
                    alwaysAutoRefresh = true
                    autoLoadFromStorage = true
                    autoSaveToStorage = true
                }
                install(Realtime)
            }
        }
    }
}
