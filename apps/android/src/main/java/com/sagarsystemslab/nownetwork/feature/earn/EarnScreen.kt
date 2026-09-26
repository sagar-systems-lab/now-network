package com.sagarsystemslab.nownetwork.feature.earn

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.sagarsystemslab.nownetwork.feature.common.ShellScreen

@Composable
fun EarnScreen() {
    ShellScreen(
        modifier = Modifier.testTag("screen-earn"),
        title = "EARN",
        headline = "Earn nearby",
        body = "Nearby refresh opportunities will appear here when work is available.",
    )
}
