package com.sagarsystemslab.nownetwork.designsystem

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

val LocalNowMotionAllowed = staticCompositionLocalOf { true }

@Composable
fun rememberNowMotionEnabled(): Boolean {
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) }
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return resumed && LocalNowMotionAllowed.current && ValueAnimator.areAnimatorsEnabled()
}

fun nowMotionDuration(
    enabled: Boolean,
    durationMillis: Int,
): Int = if (enabled) durationMillis else 0

@Composable
fun Modifier.nowPulseOnChange(
    key: Any?,
    maxScale: Float = NowMotion.ChangeScale,
    durationMillis: Int = NowMotion.BaseMillis,
    pulseOnInitial: Boolean = false,
): Modifier {
    val motionEnabled = rememberNowMotionEnabled()
    val scale = remember { Animatable(1f) }
    var initialized by remember { mutableStateOf(false) }

    LaunchedEffect(key, motionEnabled) {
        val firstRun = !initialized
        if (firstRun) {
            initialized = true
        }

        if (!motionEnabled || (firstRun && !pulseOnInitial)) {
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
fun Modifier.nowLivePulse(active: Boolean): Modifier {
    val enabled = rememberNowMotionEnabled()
    val scale = remember { Animatable(1f) }
    LaunchedEffect(active, enabled) {
        scale.snapTo(1f)
        if (active && enabled) repeat(3) {
            scale.animateTo(NowMotion.LiveScale, tween(NowMotion.LivePulseHalfCycleMillis))
            scale.animateTo(1f, tween(NowMotion.LivePulseHalfCycleMillis))
        }
    }
    return graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}
