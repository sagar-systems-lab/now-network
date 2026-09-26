package com.sagarsystemslab.nownetwork.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.sagarsystemslab.nownetwork.feature.common.ShellScreen

@Composable
fun NowScreen() {
    ShellScreen(
        modifier = Modifier.testTag("screen-now"),
        title = "NOW",
        headline = "What do you need to know?",
        body = "Live and stale nearby states will appear here when current data is available.",
    )
}
