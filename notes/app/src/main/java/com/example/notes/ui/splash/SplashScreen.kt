package com.example.notes.ui.splash

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

@Composable
fun BrandMark(
    sizeDp: Int = 96,
    modifier: Modifier = Modifier
) {
    Image(
        painter = painterResource(id = R.drawable.logo_dark),
        contentDescription = "Notes Logo",
        modifier = modifier.size(sizeDp.dp)
    )
}

/**
 * Ultra-smooth Splash Screen transitioning gracefully into the destination.
 * Uses spring-loaded physics and animated notes vector in Deep Forest colors.
 */
@Composable
fun SplashScreen(
    onTimeout: () -> Unit
) {
    LaunchedEffect(Unit) {
        // Quick 350ms splash to feel instant and responsive
        delay(350)
        onTimeout()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
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
            modifier = Modifier.fillMaxSize()
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
    }
}
