package com.sagarsystemslab.nownetwork.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

enum class NowStatusTone {
    LIVE,
    AGING,
    STALE,
    CONFLICT,
    INFO,
}

enum class NowNoticeTone {
    INFO,
    NEUTRAL,
    SUCCESS,
    WARNING,
    ERROR,
}

@Composable
fun NowPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 50.dp),
        enabled = enabled,
        shape = NowShapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = NowColors.Blue600,
            contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = NowColors.Ink200,
            disabledContentColor = NowColors.Ink500,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = NowSpacing.Space4,
            vertical = NowSpacing.Space3,
        ),
    ) {
        Text(
            text = text,
            style = NowType.LabelL,
        )
    }
}

@Composable
fun NowSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 50.dp),
        enabled = enabled,
        shape = NowShapes.medium,
        border = BorderStroke(
            width = 1.dp,
            color = if (enabled) NowColors.BorderDefault else NowColors.BorderSubtle,
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = NowColors.Ink800,
            disabledContentColor = NowColors.Ink400,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = NowSpacing.Space4,
            vertical = NowSpacing.Space3,
        ),
    ) {
        Text(
            text = text,
            style = NowType.LabelL,
        )
    }
}

@Composable
fun NowStatusChip(
    label: String,
    tone: NowStatusTone,
    modifier: Modifier = Modifier,
    accessibilityLabel: String = label,
) {
    val palette = nowStatusPalette(tone)
    val motionEnabled = rememberNowMotionEnabled()
    val duration = nowMotionDuration(
        enabled = motionEnabled,
        durationMillis = NowMotion.StateMillis,
    )
    val foreground by animateColorAsState(
        targetValue = palette.foreground,
        animationSpec = tween(duration),
        label = "now-status-foreground",
    )
    val background by animateColorAsState(
        targetValue = palette.background,
        animationSpec = tween(duration),
        label = "now-status-background",
    )
    val border by animateColorAsState(
        targetValue = palette.border,
        animationSpec = tween(duration),
        label = "now-status-border",
    )
    val dot by animateColorAsState(
        targetValue = palette.dot,
        animationSpec = tween(duration),
        label = "now-status-dot",
    )

    Surface(
        modifier = modifier.semantics {
            contentDescription = accessibilityLabel
            stateDescription = label
        },
        shape = NowShapes.extraLarge,
        color = background,
        border = BorderStroke(1.dp, border),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = 9.dp,
                vertical = 5.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(7.dp),
                shape = NowShapes.extraLarge,
                color = dot,
            ) {}
            Text(
                text = label,
                style = NowType.LabelM,
                color = foreground,
            )
        }
    }
}

@Composable
fun NowNotice(
    body: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    tone: NowNoticeTone = NowNoticeTone.INFO,
) {
    val palette = nowNoticePalette(tone)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = NowShapes.medium,
        color = palette.background,
        border = BorderStroke(1.dp, palette.border),
    ) {
        Column(
            modifier = Modifier.padding(NowSpacing.Space3),
            verticalArrangement = Arrangement.spacedBy(NowSpacing.Space1),
        ) {
            if (!title.isNullOrBlank()) {
                Text(
                    text = title,
                    style = NowType.TitleS,
                    color = palette.foreground,
                )
            }
            Text(
                text = body,
                style = NowType.BodyM,
                color = palette.foreground,
            )
        }
    }
}

@Composable
fun NowTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = singleLine,
        isError = isError,
        label = {
            Text(
                text = label,
                style = NowType.BodyM,
            )
        },
        supportingText = supportingText?.let { text ->
            {
                Text(
                    text = text,
                    style = NowType.BodyS,
                )
            }
        },
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        shape = NowShapes.medium,
    )
}

private data class StatusPalette(
    val foreground: Color,
    val background: Color,
    val border: Color,
    val dot: Color,
)

private data class NoticePalette(
    val foreground: Color,
    val background: Color,
    val border: Color,
)

@Composable
private fun nowStatusPalette(tone: NowStatusTone): StatusPalette =
    when (tone) {
        NowStatusTone.LIVE -> StatusPalette(
            foreground = NowColors.LiveText,
            background = NowColors.LiveSoft,
            border = NowColors.LiveBorder,
            dot = NowColors.LiveDot,
        )
        NowStatusTone.AGING -> StatusPalette(
            foreground = NowColors.AgingText,
            background = NowColors.AgingSoft,
            border = NowColors.AgingBorder,
            dot = NowColors.AgingDot,
        )
        NowStatusTone.STALE -> StatusPalette(
            foreground = NowColors.StaleText,
            background = NowColors.StaleSoft,
            border = NowColors.StaleBorder,
            dot = NowColors.StaleDot,
        )
        NowStatusTone.CONFLICT -> StatusPalette(
            foreground = NowColors.ConflictText,
            background = NowColors.ConflictSoft,
            border = NowColors.ConflictBorder,
            dot = NowColors.ConflictDot,
        )
        NowStatusTone.INFO -> StatusPalette(
            foreground = NowColors.InfoText,
            background = NowColors.InfoSoft,
            border = NowColors.InfoBorder,
            dot = NowColors.Blue500,
        )
    }

@Composable
private fun nowNoticePalette(tone: NowNoticeTone): NoticePalette =
    when (tone) {
        NowNoticeTone.INFO -> NoticePalette(
            foreground = NowColors.InfoText,
            background = NowColors.InfoSoft,
            border = NowColors.InfoBorder,
        )
        NowNoticeTone.NEUTRAL -> NoticePalette(
            foreground = NowColors.Ink600,
            background = NowColors.StaleSoft,
            border = NowColors.StaleBorder,
        )
        NowNoticeTone.SUCCESS -> NoticePalette(
            foreground = NowColors.LiveText,
            background = NowColors.LiveSoft,
            border = NowColors.LiveBorder,
        )
        NowNoticeTone.WARNING -> NoticePalette(
            foreground = NowColors.AgingText,
            background = NowColors.AgingSoft,
            border = NowColors.AgingBorder,
        )
        NowNoticeTone.ERROR -> NoticePalette(
            foreground = NowColors.ConflictText,
            background = NowColors.ConflictSoft,
            border = NowColors.ConflictBorder,
        )
    }
