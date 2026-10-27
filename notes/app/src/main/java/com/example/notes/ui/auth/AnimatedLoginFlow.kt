package com.example.notes.ui.auth

import android.app.Activity
import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.notes.data.local.AuthStore
import com.example.notes.ui.splash.AnimatedNotesVector
import com.example.notes.ui.theme.Forest700
import com.example.notes.ui.theme.Forest800
import com.example.notes.ui.theme.Forest900
import com.example.notes.ui.theme.Forest950
import com.example.notes.ui.theme.Mist100
import com.example.notes.ui.theme.Sage400
import com.example.notes.util.Logger
import com.google.android.gms.auth.api.identity.BeginSignInRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.SignInClient
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.delay

private const val WEB_CLIENT_ID = "506282696730-jmrbedtsp0ce2onj5q5uo5hcg8k89lo6.apps.googleusercontent.com"

@Composable
fun AnimatedLoginFlow(
    onAuthenticated: () -> Unit,
    onBack: () -> Unit,
    isEntryPoint: Boolean = false,
    startAsSplash: Boolean = true
) {
    val context = LocalContext.current
    val viewModel: AuthViewModel = viewModel(
        factory = AuthViewModel.factory(context.applicationContext as Application)
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    var isSplashPhase by remember { mutableStateOf(startAsSplash) }
    var contentVisible by remember { mutableStateOf(!startAsSplash) }

    LaunchedEffect(Unit) {
        if (startAsSplash) {
            delay(400)
            isSplashPhase = false
            delay(120)
            contentVisible = true
        } else {
            contentVisible = true
        }
    }

    // Google Identity One-Tap Client (uses Web Client ID — immune to package name changes!)
    val oneTapClient: SignInClient = remember(context) { Identity.getSignInClient(context) }
    var inFlight by remember { mutableStateOf(false) }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        inFlight = false
        Logger.d(
            "AnimatedLoginFlow",
            "GoogleSignIn launcher result: code=${result.resultCode}, intent=${result.data}"
        )
        val credential = try {
            oneTapClient.getSignInCredentialFromIntent(result.data)
        } catch (e: ApiException) {
            Logger.w("AnimatedLoginFlow", "getSignInCredential failed status=${e.statusCode}", e)
            viewModel.clearGoogleError()
            return@rememberLauncherForActivityResult
        }
        val idToken = credential.googleIdToken
        Logger.d(
            "AnimatedLoginFlow",
            "getSignInCredential succeeded: email=${credential.id}, hasToken=${idToken != null}"
        )
        if (idToken != null) {
            viewModel.submitGoogle(idToken)
        } else {
            viewModel.setGoogleError("No Google ID token returned.")
        }
    }

    fun launchGoogleSignIn() {
        if (inFlight) return
        inFlight = true
        viewModel.beginGoogleAttempt()

        val signInRequest = BeginSignInRequest.builder()
            .setGoogleIdTokenRequestOptions(
                BeginSignInRequest.GoogleIdTokenRequestOptions.builder()
                    .setSupported(true)
                    .setServerClientId(WEB_CLIENT_ID)
                    .setFilterByAuthorizedAccounts(false)
                    .build()
            )
            .setAutoSelectEnabled(false)
            .build()

        oneTapClient.beginSignIn(signInRequest)
            .addOnSuccessListener { result ->
                Logger.d("AnimatedLoginFlow", "beginSignIn succeeded, launching picker")
                val request = IntentSenderRequest.Builder(result.pendingIntent.intentSender).build()
                googleSignInLauncher.launch(request)
            }
            .addOnFailureListener { e ->
                Logger.w("AnimatedLoginFlow", "beginSignIn failed", e)
                inFlight = false
                viewModel.setGoogleError("Google sign-in unavailable: ${e.localizedMessage ?: e.message}")
            }
    }

    LaunchedEffect(state.isAuthenticated) {
        if (state.isAuthenticated) onAuthenticated()
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (context as? Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF9FBF9))
    ) {
        val screenHeight = maxHeight

        val targetHeaderHeight = if (isSplashPhase) screenHeight else 270.dp
        val animatedHeaderHeight by animateDpAsState(
            targetValue = targetHeaderHeight,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessLow
            ),
            label = "headerHeight"
        )

        val animatedCornerRadius by animateDpAsState(
            targetValue = if (isSplashPhase) 0.dp else 40.dp,
            animationSpec = spring(stiffness = Spring.StiffnessLow),
            label = "cornerRadius"
        )

        val vectorSize by animateDpAsState(
            targetValue = if (isSplashPhase) 140.dp else 95.dp,
            animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
            label = "vectorSize"
        )

        // 1. TOP HEADER (Morphing Background Card)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(animatedHeaderHeight)
                .shadow(
                    elevation = if (isSplashPhase) 0.dp else 14.dp,
                    shape = RoundedCornerShape(bottomStart = animatedCornerRadius, bottomEnd = animatedCornerRadius)
                )
                .clip(RoundedCornerShape(bottomStart = animatedCornerRadius, bottomEnd = animatedCornerRadius))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Forest950, Forest900, Forest800)
                    )
                )
                .statusBarsPadding()
        ) {
            if (isSplashPhase) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    AnimatedNotesVector(
                        sizeDp = vectorSize,
                        tintColor = Sage400,
                        accentColor = Mist100,
                        primaryColor = Forest700
                    )
                    Spacer(Modifier.height(20.dp))
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
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (!isEntryPoint) {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.12f))
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                        Text(
                            text = "Notes.",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 30.sp,
                                letterSpacing = (-0.5).sp
                            ),
                            color = Color.White
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Your ideas, securely synced",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                            color = Mist100.copy(alpha = 0.88f)
                        )
                    }

                    AnimatedNotesVector(
                        sizeDp = vectorSize,
                        tintColor = Sage400,
                        accentColor = Mist100,
                        primaryColor = Forest700,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }
        }

        // 2. STAGGERED FORM CONTENT
        if (!isSplashPhase) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 270.dp)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Stagger 1: Welcome title
                AnimatedVisibility(
                    visible = contentVisible,
                    enter = fadeIn(tween(400, delayMillis = 40, easing = FastOutSlowInEasing)) +
                            slideInVertically(
                                initialOffsetY = { 80 },
                                animationSpec = spring(stiffness = Spring.StiffnessLow)
                            )
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Welcome to Notes",
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.4).sp
                            ),
                            color = Forest950
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Sign in to keep your notes synchronized and backed up across all your devices.",
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                            color = Color(0xFF5F6368)
                        )
                    }
                }

                Spacer(Modifier.height(32.dp))

                // Stagger 2: Google Continue Button
                AnimatedVisibility(
                    visible = contentVisible,
                    enter = fadeIn(tween(400, delayMillis = 100, easing = FastOutSlowInEasing)) +
                            slideInVertically(
                                initialOffsetY = { 90 },
                                animationSpec = spring(stiffness = Spring.StiffnessLow)
                            )
                ) {
                    Surface(
                        onClick = { launchGoogleSignIn() },
                        enabled = !state.isGoogleLoading && !inFlight,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(28.dp),
                        color = Color.White,
                        shadowElevation = 3.dp,
                        border = BorderStroke(1.dp, Color(0xFFDADCE0))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (state.isGoogleLoading || inFlight) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.5.dp,
                                    modifier = Modifier.size(24.dp),
                                    color = Color(0xFF1A73E8)
                                )
                                Spacer(Modifier.width(14.dp))
                                Text(
                                    text = "Connecting to Google…",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                                    color = Forest950
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF4285F4)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "G",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                }
                                Spacer(Modifier.width(14.dp))
                                Text(
                                    text = "Continue with Google",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = Forest950
                                )
                            }
                        }
                    }
                }

                // Error message banner
                if (state.errorMessage != null) {
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFFFFDAD6),
                        contentColor = Color(0xFF410002)
                    ) {
                        Text(
                            text = state.errorMessage ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Stagger 3: Guest / Skip Button
                AnimatedVisibility(
                    visible = contentVisible,
                    enter = fadeIn(tween(400, delayMillis = 160, easing = FastOutSlowInEasing)) +
                            slideInVertically(
                                initialOffsetY = { 100 },
                                animationSpec = spring(stiffness = Spring.StiffnessLow)
                            )
                ) {
                    OutlinedButton(
                        onClick = onAuthenticated,
                        shape = RoundedCornerShape(28.dp),
                        border = BorderStroke(1.dp, Color(0xFFE0E2EC)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        Text(
                            text = "Continue as Guest",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                            color = Color(0xFF44474E)
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = Color(0xFF44474E)
                        )
                    }
                }

                Spacer(Modifier.height(36.dp))

                // Stagger 4: Security & Privacy badge
                AnimatedVisibility(
                    visible = contentVisible,
                    enter = fadeIn(tween(400, delayMillis = 220, easing = FastOutSlowInEasing))
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = Forest700,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "End-to-End Encrypted & Private",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = Forest700
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Your notes are private to you. We never sell or inspect your personal data.",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                            color = Color(0xFF8E919A),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Saved Accounts if previously logged in
                if (state.savedAccounts.isNotEmpty()) {
                    Spacer(Modifier.height(28.dp))
                    Text(
                        text = "Saved accounts",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFF44474E),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    )
                    state.savedAccounts.forEach { session ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    viewModel.switchAccount(session.email) {
                                        onAuthenticated()
                                    }
                                },
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            border = BorderStroke(1.dp, Color(0xFFE0E2EC))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        text = session.email,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                        color = Forest950
                                    )
                                    Text(
                                        text = "Tap to switch",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF8E919A)
                                    )
                                }
                                IconButton(onClick = { viewModel.removeAccount(session.email) }) {
                                    Icon(
                                        imageVector = Icons.Default.Email,
                                        contentDescription = "Remove",
                                        tint = Color(0xFF8E919A),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
