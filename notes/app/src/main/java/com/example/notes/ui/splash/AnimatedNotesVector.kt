package com.example.notes.ui.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.notes.ui.theme.Forest700
import com.example.notes.ui.theme.Forest800
import com.example.notes.ui.theme.Forest900
import com.example.notes.ui.theme.Mist100
import com.example.notes.ui.theme.Sage400

/**
 * High-performance, GPU-accelerated animated Notes Vector.
 * 
 * Renders a stylized organic Notebook with floating writing pen and subtle
 * floating particles. Designed to run smoothly at 60-120fps with zero third-party
 * runtime dependencies (like Lottie/Rive overhead).
 */
@Composable
fun AnimatedNotesVector(
    modifier: Modifier = Modifier,
    sizeDp: Dp = 130.dp,
    tintColor: Color = Sage400,
    accentColor: Color = Mist100,
    primaryColor: Color = Forest800
) {
    // Pen writing stroke progress (0f..1f)
    val writingProgress = remember { Animatable(0f) }
    // Subtle float / hover oscillation for the notebook & pen
    val hoverOffset = remember { Animatable(0f) }
    // Particle pulse effect
    val particlePulse = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        writingProgress.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessLow
            )
        )
    }

    LaunchedEffect(Unit) {
        hoverOffset.animateTo(
            targetValue = 6f,
            animationSpec = infiniteRepeatable(
                animation = tween(1800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            )
        )
    }

    LaunchedEffect(Unit) {
        particlePulse.animateTo(
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(2200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            )
        )
    }

    Box(
        modifier = modifier
            .size(sizeDp)
            .graphicsLayer {
                translationY = hoverOffset.value
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            // 1. Decorative Glow / Ambient Shadow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(tintColor.copy(alpha = 0.22f), Color.Transparent),
                    center = Offset(width * 0.5f, height * 0.52f),
                    radius = width * 0.48f
                ),
                radius = width * 0.48f,
                center = Offset(width * 0.5f, height * 0.52f)
            )

            // 2. Notebook Back Page (Slightly angled/staggered)
            val backPageWidth = width * 0.58f
            val backPageHeight = height * 0.68f
            val backLeft = width * 0.24f
            val backTop = height * 0.16f

            drawRoundRect(
                color = tintColor.copy(alpha = 0.35f),
                topLeft = Offset(backLeft + 8f, backTop - 4f),
                size = Size(backPageWidth, backPageHeight),
                cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx())
            )

            // 3. Main Notebook Body
            val mainLeft = width * 0.20f
            val mainTop = height * 0.18f
            val mainWidth = width * 0.56f
            val mainHeight = height * 0.68f
            val cornerRadiusPx = 18.dp.toPx()

            // Outer cover
            drawRoundRect(
                brush = Brush.linearGradient(
                    colors = listOf(primaryColor, primaryColor.copy(alpha = 0.88f)),
                    start = Offset(mainLeft, mainTop),
                    end = Offset(mainLeft + mainWidth, mainTop + mainHeight)
                ),
                topLeft = Offset(mainLeft, mainTop),
                size = Size(mainWidth, mainHeight),
                cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx)
            )

            // Notebook Spine binding (Left accent strip)
            val spineWidth = mainWidth * 0.16f
            val spinePath = Path().apply {
                addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        left = mainLeft,
                        top = mainTop,
                        right = mainLeft + spineWidth,
                        bottom = mainTop + mainHeight,
                        topLeftCornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                        bottomLeftCornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx)
                    )
                )
            }
            drawPath(spinePath, color = tintColor)

            // Decorative stitch marks on spine
            val stitchCount = 4
            val stitchSpacing = (mainHeight - 32.dp.toPx()) / (stitchCount - 1)
            for (i in 0 until stitchCount) {
                val y = mainTop + 16.dp.toPx() + (i * stitchSpacing)
                drawLine(
                    color = primaryColor,
                    start = Offset(mainLeft + (spineWidth * 0.3f), y),
                    end = Offset(mainLeft + (spineWidth * 0.7f), y),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // 4. Inner Paper Texture lines (simulating handwritten notes)
            val lineLeft = mainLeft + spineWidth + 10.dp.toPx()
            val lineRight = mainLeft + mainWidth - 10.dp.toPx()
            val totalLineProgress = writingProgress.value

            val lineYPositions = listOf(
                mainTop + (mainHeight * 0.26f),
                mainTop + (mainHeight * 0.42f),
                mainTop + (mainHeight * 0.58f),
                mainTop + (mainHeight * 0.74f)
            )

            val lineWidthFactors = listOf(0.85f, 0.95f, 0.65f, 0.40f)

            lineYPositions.forEachIndexed { index, yPos ->
                val lineMaxLen = (lineRight - lineLeft) * lineWidthFactors[index]
                // Each line animates progressively
                val startProgress = index * 0.2f
                val effectiveProgress = ((totalLineProgress - startProgress) / 0.4f).coerceIn(0f, 1f)

                if (effectiveProgress > 0f) {
                    val currentLen = lineMaxLen * effectiveProgress
                    drawLine(
                        color = accentColor.copy(alpha = if (index == 0) 0.95f else 0.75f),
                        start = Offset(lineLeft, yPos),
                        end = Offset(lineLeft + currentLen, yPos),
                        strokeWidth = if (index == 0) 3.5.dp.toPx() else 2.5.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }

            // 5. Stylized Floating Fountain Pen / Stylus
            drawAnimatedStylus(
                width = width,
                height = height,
                progress = writingProgress.value,
                primaryColor = Forest900,
                nibColor = tintColor,
                glowColor = accentColor
            )

            // 6. Sparkling/Inspiration Particles around the notebook
            val particleAlpha = (1f - particlePulse.value) * 0.7f
            val particleExpand = particlePulse.value * 12.dp.toPx()

            // Particle 1 (Top right)
            drawCircle(
                color = tintColor.copy(alpha = particleAlpha),
                radius = 3.dp.toPx(),
                center = Offset(width * 0.82f + particleExpand, height * 0.20f - particleExpand)
            )
            // Particle 2 (Bottom left)
            drawCircle(
                color = accentColor.copy(alpha = particleAlpha * 0.8f),
                radius = 2.dp.toPx(),
                center = Offset(width * 0.16f - particleExpand, height * 0.70f + particleExpand)
            )
            // Particle 3 (Sparkle star top left)
            drawSparkle(
                center = Offset(width * 0.22f, height * 0.12f),
                sizePx = 8.dp.toPx(),
                color = accentColor.copy(alpha = 0.6f + (particleAlpha * 0.4f))
            )
        }
    }
}

/**
 * Draws the slanted fountain stylus drawing the notes.
 */
private fun DrawScope.drawAnimatedStylus(
    width: Float,
    height: Float,
    progress: Float,
    primaryColor: Color,
    nibColor: Color,
    glowColor: Color
) {
    // Stylus position correlates with writing motion
    val penTipX = width * (0.42f + (0.28f * progress))
    val penTipY = height * (0.64f + (0.04f * kotlin.math.sin(progress * Math.PI.toFloat() * 2f)))

    val penAngleRad = -Math.PI / 4.0 // 45 degree tilt
    val cosA = kotlin.math.cos(penAngleRad).toFloat()
    val sinA = kotlin.math.sin(penAngleRad).toFloat()

    val penLength = width * 0.38f
    val penRadius = width * 0.024f

    val tailX = penTipX + (penLength * cosA)
    val tailY = penTipY + (penLength * sinA)

    // Nib (Golden/Sage triangle)
    val nibLength = penLength * 0.22f
    val nibBaseX = penTipX + (nibLength * cosA)
    val nibBaseY = penTipY + (nibLength * sinA)

    val nibPerpX = -sinA * penRadius
    val nibPerpY = cosA * penRadius

    val nibPath = Path().apply {
        moveTo(penTipX, penTipY)
        lineTo(nibBaseX + nibPerpX, nibBaseY + nibPerpY)
        lineTo(nibBaseX - nibPerpX, nibBaseY - nibPerpY)
        close()
    }
    drawPath(nibPath, color = nibColor)

    // Pen Body (Sleek dark barrel)
    val bodyPath = Path().apply {
        moveTo(nibBaseX + nibPerpX, nibBaseY + nibPerpY)
        lineTo(tailX + nibPerpX, tailY + nibPerpY)
        lineTo(tailX - nibPerpX, tailY - nibPerpY)
        lineTo(nibBaseX - nibPerpX, nibBaseY - nibPerpY)
        close()
    }
    drawPath(
        bodyPath,
        brush = Brush.linearGradient(
            colors = listOf(primaryColor, Forest700),
            start = Offset(nibBaseX, nibBaseY),
            end = Offset(tailX, tailY)
        )
    )

    // Clip ring accent
    val ringX = nibBaseX + ((tailX - nibBaseX) * 0.75f)
    val ringY = nibBaseY + ((tailY - nibBaseY) * 0.75f)
    drawLine(
        color = glowColor,
        start = Offset(ringX + nibPerpX * 1.1f, ringY + nibPerpY * 1.1f),
        end = Offset(ringX - nibPerpX * 1.1f, ringY - nibPerpY * 1.1f),
        strokeWidth = 2.dp.toPx(),
        cap = StrokeCap.Round
    )
}

/**
 * Draws a 4-point sparkle star.
 */
private fun DrawScope.drawSparkle(center: Offset, sizePx: Float, color: Color) {
    val path = Path().apply {
        moveTo(center.x, center.y - sizePx)
        quadraticTo(center.x, center.y, center.x + sizePx, center.y)
        quadraticTo(center.x, center.y, center.x, center.y + sizePx)
        quadraticTo(center.x, center.y, center.x - sizePx, center.y)
        quadraticTo(center.x, center.y, center.x, center.y - sizePx)
        close()
    }
    drawPath(path, color = color)
}
