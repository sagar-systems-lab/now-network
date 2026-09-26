package com.sagarsystemslab.nownetwork.feature.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

@Composable
fun rememberVisibleServerTime(
    serverNowMillis: () -> Long,
): Long {
    var nowMillis by remember { mutableLongStateOf(serverNowMillis()) }

    LaunchedEffect(serverNowMillis) {
        while (true) {
            nowMillis = serverNowMillis()
            delay(1_000L)
        }
    }

    return nowMillis
}
