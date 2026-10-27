package com.example.notes.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 60fps Micro-animated illustrations for empty states, inspired by LottieFiles & Mobbin.
 * Runs smoothly on Compose Canvas with spring/physics easing.
 */
@Composable
fun AnimatedEmptyNotes(
    modifier: Modifier = Modifier,
    size: Dp = 130.dp,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    outlineColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val infiniteTransition = rememberInfiniteTransition(label = "notesFloat")
    val floatOffset by infiniteTransition.animateFloat(
        initialValue = -5f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sheetFloat"
    )

    val lineProgress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        lineProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing)
        )
    }

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val stroke = 3.dp.toPx()

            // Back note sheet (static subtle tilt)
            drawRoundRect(
                color = accentColor.copy(alpha = 0.20f),
                topLeft = Offset(w * 0.32f, h * 0.16f),
                size = Size(w * 0.58f, h * 0.72f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx()),
            )

            // Front note sheet (gently floating up and down)
            val frontY = h * 0.26f + floatOffset
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(w * 0.18f, frontY),
                size = Size(w * 0.58f, h * 0.65f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()),
            )
            drawRoundRect(
                color = outlineColor.copy(alpha = 0.8f),
                topLeft = Offset(w * 0.18f, frontY),
                size = Size(w * 0.58f, h * 0.65f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()),
                style = Stroke(width = stroke)
            )

            // Note lines drawing in smoothly
            val cx = w * 0.18f
            val cw = w * 0.58f
            val lineWidths = listOf(0.70f, 0.55f, 0.35f)
            lineWidths.forEachIndexed { i, maxF ->
                val f = maxF * lineProgress.value
                val y = frontY + (h * 0.65f) * (0.30f + i * 0.17f)
                drawLine(
                    color = outlineColor.copy(alpha = 0.60f),
                    start = Offset(cx + cw * 0.14f, y),
                    end = Offset(cx + cw * (0.14f + f), y),
                    strokeWidth = 2.5.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

@Composable
fun AnimatedEmptySearch(
    modifier: Modifier = Modifier,
    size: Dp = 130.dp,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    outlineColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val infiniteTransition = rememberInfiniteTransition(label = "searchOrbit")
    val angle by infiniteTransition.animateFloat(
        initialValue = -8f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glassWiggle"
    )

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val r = w * 0.28f
            val cx = w * 0.44f
            val cy = h * 0.44f + (angle * 0.5f)

            // Magnifying glass lens
            drawCircle(
                color = accentColor.copy(alpha = 0.15f),
                radius = r,
                center = Offset(cx, cy)
            )
            drawCircle(
                color = outlineColor,
                radius = r,
                center = Offset(cx, cy),
                style = Stroke(width = 4.dp.toPx())
            )

            // Handle
            drawLine(
                color = outlineColor,
                start = Offset(cx + r * 0.70f, cy + r * 0.70f),
                end = Offset(cx + r * 1.50f, cy + r * 1.50f),
                strokeWidth = 5.dp.toPx(),
                cap = StrokeCap.Round
            )

            // Mini question mark / sparkle inside
            drawLine(
                color = accentColor,
                start = Offset(cx - r * 0.25f, cy),
                end = Offset(cx + r * 0.25f, cy),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
fun AnimatedEmptyTrash(
    modifier: Modifier = Modifier,
    size: Dp = 130.dp,
    outlineColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val infiniteTransition = rememberInfiniteTransition(label = "trashLid")
    val lidAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -12f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "lidFloat"
    )

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val stroke = 3.5.dp.toPx()

            // Trash can body
            val bodyPath = Path().apply {
                moveTo(w * 0.32f, h * 0.40f)
                lineTo(w * 0.36f, h * 0.82f)
                lineTo(w * 0.64f, h * 0.82f)
                lineTo(w * 0.68f, h * 0.40f)
                close()
            }
            drawPath(path = bodyPath, color = outlineColor, style = Stroke(width = stroke))

            // Body vertical ribs
            drawLine(
                color = outlineColor.copy(alpha = 0.5f),
                start = Offset(w * 0.44f, h * 0.48f),
                end = Offset(w * 0.45f, h * 0.75f),
                strokeWidth = stroke * 0.8f,
                cap = StrokeCap.Round
            )
            drawLine(
                color = outlineColor.copy(alpha = 0.5f),
                start = Offset(w * 0.56f, h * 0.48f),
                end = Offset(w * 0.55f, h * 0.75f),
                strokeWidth = stroke * 0.8f,
                cap = StrokeCap.Round
            )

            // Animated lid with tilt
            val lidY = h * 0.37f + (lidAngle * 0.4f)
            drawLine(
                color = outlineColor,
                start = Offset(w * 0.28f, lidY),
                end = Offset(w * 0.72f, lidY + (lidAngle * 0.8f)),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
    }
}
