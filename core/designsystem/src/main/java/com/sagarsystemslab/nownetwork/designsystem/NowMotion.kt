package com.sagarsystemslab.nownetwork.designsystem

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

@Composable
fun rememberNowMotionEnabled(): Boolean =
    ValueAnimator.areAnimatorsEnabled()

fun nowMotionDuration(
    enabled: Boolean,
    durationMillis: Int,
): Int = if (enabled) durationMillis else 0

@Composable
fun Modifier.nowPulseOnChange(
    key: Any?,
    maxScale: Float = NowMotion.ChangeScale,
    durationMillis: Int = NowMotion.BaseMillis,
): Modifier {
    val motionEnabled = rememberNowMotionEnabled()
    val scale = remember { Animatable(1f) }
    var initialized by remember { mutableStateOf(false) }

    LaunchedEffect(key, motionEnabled) {
        if (!initialized) {
            initialized = true
            return@LaunchedEffect
        }

        if (!motionEnabled) {
            scale.snapTo(1f)
            return@LaunchedEffect
        }

        val halfDuration = (durationMillis / 2).coerceAtLeast(1)
        scale.snapTo(1f)
        scale.animateTo(
            targetValue = maxScale,
            animationSpec = tween(halfDuration),
        )
        scale.animateTo(
            targetValue = 1f,
            animationSpec = tween(halfDuration),
        )
    }

    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

@Composable
fun Modifier.nowLivePulse(
    active: Boolean,
): Modifier {
    val motionEnabled = rememberNowMotionEnabled()
    if (!active || !motionEnabled) {
        return this
    }

    val transition = rememberInfiniteTransition(
        label = "now-live-pulse",
    )
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = NowMotion.LiveScale,
        animationSpec = infiniteRepeatable(
            animation = tween(NowMotion.LivePulseHalfCycleMillis),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "now-live-scale",
    )

    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
