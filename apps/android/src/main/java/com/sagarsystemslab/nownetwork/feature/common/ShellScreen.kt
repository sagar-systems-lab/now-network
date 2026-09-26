package com.sagarsystemslab.nownetwork.feature.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.sagarsystemslab.nownetwork.designsystem.NowColors
import com.sagarsystemslab.nownetwork.designsystem.NowSpacing

@Composable
fun ShellScreen(
    title: String,
    headline: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                PaddingValues(
                    start = NowSpacing.Space4,
                    top = NowSpacing.Space5,
                    end = NowSpacing.Space4,
                    bottom = NowSpacing.Space6,
                ),
            ),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = NowColors.Ink950,
        )
        Spacer(Modifier.height(NowSpacing.Space4))
        Text(
            text = headline,
            style = MaterialTheme.typography.headlineMedium,
            color = NowColors.Ink950,
        )
        Spacer(Modifier.height(NowSpacing.Space2))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = NowColors.Ink500,
        )
    }
}
