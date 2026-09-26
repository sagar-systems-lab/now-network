package com.sagarsystemslab.nownetwork.feature.activity

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.sagarsystemslab.nownetwork.feature.common.ShellScreen

@Composable
fun ActivityScreen() {
    ShellScreen(
        modifier = Modifier.testTag("screen-activity"),
        title = "ACTIVITY",
        headline = "Your activity",
        body = "Pending and completed refreshes, earnings, and receipts will appear here.",
    )
}
