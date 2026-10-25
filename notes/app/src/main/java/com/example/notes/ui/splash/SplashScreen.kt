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
    LaunchedEffect(Unit) {
        delay(800)
        onTimeout()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
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
                BrandMark(sizeDp = 96)
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "Notes",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontSize = 32.sp,
                        letterSpacing = (-0.5).sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    ),
                    color = Color(0xFF202124)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Capture what's on your mind",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF5F6368)
                )
            }
        }
    }
}
