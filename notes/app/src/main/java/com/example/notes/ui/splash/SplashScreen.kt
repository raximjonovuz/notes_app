package com.example.notes.ui.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.notes.R
import com.example.notes.ui.theme.Forest700
import com.example.notes.ui.theme.Forest800
import com.example.notes.ui.theme.Forest900
import com.example.notes.ui.theme.Forest950
import com.example.notes.ui.theme.Mist100
import com.example.notes.ui.theme.Sage400
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun BrandMark(
    sizeDp: Int = 96,
    modifier: Modifier = Modifier
) {
    Image(
        painter = painterResource(id = R.drawable.logo_light),
        contentDescription = "Notes Logo",
        modifier = modifier.size(sizeDp.dp)
    )
}

/**
 * Ultra-smooth Splash Screen transitioning gracefully into the destination (V2 - Tier 1 Motion).
 *
 * Features:
 * - Fluid spring pen writing in Deep Forest colors
 * - Center Circular Zoom-Reveal transition into the destination
 * - Sage ring shockwave expanding outward
 * - Tap-to-Skip support for fast user flow
 * - Native haptic feedback
 */
@Composable
fun SplashScreen(
    onTimeout: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Exit transition animations
    val zoomScale = remember { Animatable(1f) }
    val contentAlpha = remember { Animatable(1f) }
    val revealRadius = remember { Animatable(0f) }
    val ringAlpha = remember { Animatable(0f) }

    var isExiting by remember { mutableStateOf(false) }

    suspend fun executeExit() {
        if (isExiting) return
        isExiting = true
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)

        // Simultaneous zoom, fade, and circular shockwave
        kotlinx.coroutines.coroutineScope {
            launch {
                zoomScale.animateTo(
                    targetValue = 1.35f,
                    animationSpec = tween(durationMillis = 280, easing = FastOutLinearInEasing)
                )
            }
            launch {
                contentAlpha.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 240, easing = LinearOutSlowInEasing)
                )
            }
            launch {
                revealRadius.animateTo(
                    targetValue = 1.2f, // 0..1+ normalized to screen diagonal
                    animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing)
                )
            }
            launch {
                ringAlpha.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 100)
                )
                ringAlpha.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 220)
                )
            }
        }
        onTimeout()
    }

    LaunchedEffect(Unit) {
        // Crisp 450ms intro duration before fluid reveal
        delay(450)
        executeExit()
    }

    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                // Tap-to-Skip for instant responsiveness
                if (!isExiting) {
                    scope.launch {
                        executeExit()
                    }
                }
            }
            .background(
                Brush.verticalGradient(
                    colors = listOf(Forest950, Forest900, Forest800)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoomScale.value
                    scaleY = zoomScale.value
                    alpha = contentAlpha.value
                }
        ) {
            AnimatedNotesVector(
                sizeDp = 140.dp,
                tintColor = Sage400,
                accentColor = Mist100,
                primaryColor = Forest700
            )

            Spacer(Modifier.height(24.dp))

            Text(
                text = "Notes.",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.8).sp
                ),
                color = Color.White
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = "Capture your thoughts effortlessly",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 15.sp,
                    letterSpacing = 0.2.sp
                ),
                color = Mist100.copy(alpha = 0.85f)
            )
        }

        // Circular Zoom Reveal & Sage Shockwave overlay
        if (revealRadius.value > 0f) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val maxRadius = kotlin.math.hypot(size.width, size.height) / 1.7f
                val currentRadius = maxRadius * revealRadius.value

                // Sage shockwave ring
                if (ringAlpha.value > 0f) {
                    drawCircle(
                        color = Sage400.copy(alpha = ringAlpha.value * 0.75f),
                        radius = currentRadius,
                        center = center,
                        style = Stroke(width = 8.dp.toPx())
                    )
                }
            }
        }
    }
}
