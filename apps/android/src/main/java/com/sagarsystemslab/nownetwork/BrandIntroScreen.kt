package com.sagarsystemslab.nownetwork

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun BrandIntroScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    brandMark: Painter = painterResource(R.drawable.now_brand_mark),
) {
    val logoAlpha = remember { Animatable(0f) }
    val logoScale = remember { Animatable(0.86f) }
    val orbitProgress = remember { Animatable(0f) }
    val orbitAlpha = remember { Animatable(0f) }
    val haloAlpha = remember { Animatable(0f) }
    val screenAlpha = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        launch {
            haloAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(420, easing = LinearOutSlowInEasing),
            )
            haloAlpha.animateTo(
                targetValue = 0.56f,
                animationSpec = tween(520, easing = FastOutSlowInEasing),
            )
        }
        launch {
            orbitAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(260),
            )
            orbitProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(900, easing = FastOutSlowInEasing),
            )
            orbitAlpha.animateTo(
                targetValue = 0.18f,
                animationSpec = tween(260),
            )
        }
        launch {
            delay(90)
            logoAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(360, easing = LinearOutSlowInEasing),
            )
        }
        launch {
            logoScale.animateTo(
                targetValue = 1.035f,
                animationSpec = tween(700, easing = FastOutSlowInEasing),
            )
            logoScale.animateTo(
                targetValue = 1f,
                animationSpec = tween(220, easing = FastOutSlowInEasing),
            )
        }

        delay(1_180)
        screenAlpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(220, easing = FastOutSlowInEasing),
        )
        onFinished()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = screenAlpha.value }
            .background(Color(0xFF050816)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = haloAlpha.value },
        ) {
            val radius = size.minDimension * 0.44f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0x5A6D28D9),
                        Color(0x2A2474FF),
                        Color.Transparent,
                    ),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }

        Canvas(
            modifier = Modifier
                .size(300.dp)
                .graphicsLayer { alpha = orbitAlpha.value },
        ) {
            val centerPoint = Offset(size.width / 2f, size.height / 2f)
            val stroke = 1.2.dp.toPx()
            val progress = orbitProgress.value
            val cobalt = Color(0xFF2474FF)
            val cyan = Color(0xFF18C7D9)
            val violet = Color(0xFF7C5CFC)

            fun orbit(
                rotation: Float,
                width: Float,
                height: Float,
                start: Float,
                sweep: Float,
                color: Color,
            ) {
                rotate(rotation, centerPoint) {
                    drawArc(
                        color = color,
                        startAngle = start,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = Offset(
                            centerPoint.x - width / 2f,
                            centerPoint.y - height / 2f,
                        ),
                        size = Size(width, height),
                        style = Stroke(width = stroke),
                    )
                }
            }

            orbit(
                rotation = -18f + 92f * progress,
                width = size.width * 0.88f,
                height = size.height * 0.34f,
                start = 196f,
                sweep = 244f,
                color = cobalt,
            )
            orbit(
                rotation = 42f - 106f * progress,
                width = size.width * 0.82f,
                height = size.height * 0.42f,
                start = 176f,
                sweep = 232f,
                color = cyan,
            )
            orbit(
                rotation = 102f + 74f * progress,
                width = size.width * 0.76f,
                height = size.height * 0.50f,
                start = 208f,
                sweep = 218f,
                color = violet,
            )
        }

        Image(
            painter = brandMark,
            contentDescription = null,
            modifier = Modifier
                .size(214.dp)
                .graphicsLayer {
                    alpha = logoAlpha.value
                    scaleX = logoScale.value
                    scaleY = logoScale.value
                },
        )
    }
}
