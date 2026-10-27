package com.example.notes.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 60fps zero-lag bounce modifier. Does not intercept gesture pointer input,
 * ensuring zero click delay.
 */
fun Modifier.bounceScale(
    interactionSource: MutableInteractionSource,
    scaleDown: Float = 0.96f
): Modifier = composed {
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) scaleDown else 1f,
        animationSpec = spring(
            dampingRatio = 0.65f,
            stiffness = 800f
        ),
        label = "bounceScale"
    )

    this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

