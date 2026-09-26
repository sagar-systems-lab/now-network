package com.sagarsystemslab.nownetwork

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import com.sagarsystemslab.nownetwork.config.PublicRuntimeConfig
import com.sagarsystemslab.nownetwork.designsystem.NowTheme
import com.sagarsystemslab.nownetwork.repository.SessionRepository
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var runtimeConfig: PublicRuntimeConfig

    @Inject
    lateinit var sessionRepository: Lazy<SessionRepository>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NowTheme {
                LaunchedEffect(Unit) {
                    if (runtimeConfig.apiConfigured && runtimeConfig.authConfigured) {
                        sessionRepository.get().bootstrap()
                    }
                }

                NowApp()
            }
        }
    }
}
