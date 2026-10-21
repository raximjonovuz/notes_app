package com.example.notes.ui.splash

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.notes.ui.theme.Forest900
import com.example.notes.ui.theme.Forest950
import com.example.notes.ui.theme.Mist100
import com.example.notes.ui.theme.Sage400
import com.example.notes.util.LocalImageMigration
import com.example.notes.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Hard upper bound on how long the splash is willing to wait for
 * the image-path migration to settle. In practice the migration
 * finishes in well under a second (a few dozen file copies plus a
 * handful of DB updates); the timeout is a safety net for the
 * pathological case where the IO scope somehow hangs (e.g. a
 * blocked file handle on external storage).
 */
private const val SPLASH_GATE_TIMEOUT_MS = 10_000L

/* =====================================================================
 *  V6 — Theme redesign · brand mark
 *
 *  Simple, original vector logo: a rounded "page" with a folded
 *  bottom-right corner and three quiet text lines. A small leaf curl
 *  sits at the top-right to give it a lived-in, organic feel without
 *  crossing into any third-party brand territory.
 *
 *  Renders on a Forest-toned background; the paper is Sage at full
 *  strength, the fold is the deepest Forest, the leaf is Forest900.
 *  Both the splash and the login screen share the same composable so
 *  the brand stays consistent on first launch and every cold start.
 * ===================================================================== */
@Composable
fun BrandMark(
    sizeDp: Int = 96,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.Image(
        painter = androidx.compose.ui.res.painterResource(id = com.example.notes.R.drawable.logo_dark),
        contentDescription = "Notes Logo",
        modifier = modifier.size(sizeDp.dp)
    )
}

/* =====================================================================
 *  V6 — Splash
 * ===================================================================== */

@Composable
fun SplashScreen(onTimeout: () -> Unit) {
    // V5.3.4 / F-28 — wait for the one-time image-path migration to
    // settle before opening the rest of the UI. See file header comment
    // on the V5 line for the full rationale.
    var ready by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            val gate = withTimeoutOrNull(SPLASH_GATE_TIMEOUT_MS) {
                LocalImageMigration.completionSignal.await()
            }
            if (gate == null) {
                Logger.w(
                    "SplashScreen",
                    "image migration gate timed out after " +
                        "${SPLASH_GATE_TIMEOUT_MS}ms; opening UI anyway"
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Logger.w("SplashScreen",
                "unexpected error awaiting image migration", t)
        }
        delay(300)
        ready = true
        onTimeout()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Forest950, Forest900)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(animationSpec = tween(420, easing = LinearEasing)) +
                scaleIn(
                    initialScale = 0.92f,
                    animationSpec = tween(420, easing = LinearEasing)
                ),
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Spacer(Modifier.height(0.dp))
                BrandMark(sizeDp = 96)
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "Notes",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontSize = 32.sp,
                        letterSpacing = (-0.5).sp
                    ),
                    color = Mist100
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Capture what's on your mind",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Sage400.copy(alpha = 0.85f)
                )
                Spacer(Modifier.height(48.dp))
                SlowPulseLoader(label = "Loading...")
            }
        }
    }
}

@Composable
private fun SlowPulseLoader(label: String) {
    // Subtle pulse-driven dotted ring (sage against the forest
    // background). Faster than a 5-second indeterminate cycle,
    // slower than a frantic spinner — matches the calm brand.
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse-alpha"
    )
    CircularProgressIndicator(
        modifier = Modifier.size(28.dp),
        strokeWidth = 2.dp,
        color = Sage400.copy(alpha = alpha)
    )
    Spacer(Modifier.height(16.dp))
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = Sage400.copy(alpha = 0.75f)
    )
}
