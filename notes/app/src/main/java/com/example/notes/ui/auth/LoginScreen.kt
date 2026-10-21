package com.example.notes.ui.auth

import android.app.Activity
import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.notes.data.local.AuthStore
import com.example.notes.ui.splash.BrandMark
import com.example.notes.ui.theme.Forest900
import com.example.notes.ui.theme.Forest950
import com.example.notes.ui.theme.Mist100
import com.example.notes.ui.theme.Sage400
import com.example.notes.util.Logger
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.UUID

/**
 * C3 — Google Sign-In rewritten on top of **Credential Manager** +
 * **Google Identity Services**. The previous implementation used
 * `BeginSignInRequest` / `Identity.getSignInClient`, which Google
 * deprecated in 2023 and which can silently fail on emulators that
 * don't ship the One-Tap UI. Credential Manager is the system
 * credential picker (Android 14+ has a built-in bottom-sheet; older
 * versions fall back to the Play Services shim).
 *
 * Replay protection: a per-attempt **nonce** is generated on the
 * client, hashed (SHA-256, base64url, no padding), and sent both to
 * Google (as the `nonce` request param) and to the backend
 * (out-of-band). The backend stores the raw nonce for 5 minutes
 * (D1 `auth_nonces` table, see migration 0005) and rejects any
 * Google ID token whose `nonce` claim doesn't match a still-valid
 * entry. The hash on the wire and the raw form stored on the
 * server don't reveal the nonce to an attacker that only sees the
 * hash going to Google.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onAuthenticated: () -> Unit,
    onBack: () -> Unit,
    isEntryPoint: Boolean = false
) {
    val context = LocalContext.current
    val viewModel: AuthViewModel = viewModel(
        factory = AuthViewModel.factory(context.applicationContext as Application)
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    /**
     * V4.2 / C3 — Google Sign-In via Credential Manager. The screen
     * owns the `Activity` reference because the new API demands a
     * real Activity context (the framework bottom-sheet is anchored
     * to it). If the user is not inside an Activity, the launcher
     * bails out with a snackbar instead of crashing.
     */
    val credentialManager = remember(context) { CredentialManager.create(context) }
    var inFlight by remember { mutableStateOf(false) }

    suspend fun launchGoogleSignIn(activity: Activity) {
        if (inFlight) return
        inFlight = true
        viewModel.beginGoogleAttempt()
        try {
            // 1. Generate a per-attempt nonce, hash it, and forward both
            //    the raw value (to the backend) and the SHA-256 hash
            //    (to Google as a `nonce` request param).
            val rawNonce = UUID.randomUUID().toString()
            val hashedNonce = sha256Base64Url(rawNonce)
            viewModel.setPendingNonce(rawNonce)

            val webClientId =
                "506282696730-jmrbedtsp0ce2onj5q5uo5hcg8k89lo6.apps.googleusercontent.com"

            val signInWithGoogle = GetSignInWithGoogleOption.Builder(webClientId)
                // Server-side replay protection (see file header).
                .setNonce(hashedNonce)
                .build()
            val googleIdOption = GetGoogleIdOption.Builder()
                .setServerClientId(webClientId)
                .setNonce(hashedNonce)
                .setFilterByAuthorizedAccounts(false)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(signInWithGoogle)
                .addCredentialOption(googleIdOption)
                .build()

            Logger.d("LoginScreen", "CredentialManager.getCredential starting")
            val result = credentialManager.getCredential(
                request = request,
                context = activity
            )
            val credential = result.credential
            val googleId = try {
                GoogleIdTokenCredential.createFrom(credential.data)
            } catch (e: GoogleIdTokenParsingException) {
                Logger.w("LoginScreen", "GoogleIdToken parse failed", e)
                viewModel.setGoogleError("Invalid Google token received.")
                return
            }
            val idToken = googleId.idToken
            // C4 — log only the *length* of the token, never the token
            // itself (or any prefix).
            Logger.d("LoginScreen", "GoogleSignIn idToken length=${idToken.length}")
            viewModel.submitGoogle(idToken, rawNonce)
        } catch (e: GetCredentialCancellationException) {
            // User closed the bottom-sheet — treat as a no-op, not an error.
            Logger.d("LoginScreen", "Credential picker cancelled by user")
            viewModel.clearGoogleError()
        } catch (e: NoCredentialException) {
            Logger.w("LoginScreen", "No Google account on device", e)
            viewModel.setGoogleError("No Google account found on this device.")
        } catch (e: GetCredentialException) {
            Logger.w("LoginScreen", "CredentialManager failed", e)
            viewModel.setGoogleError("Google sign-in failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            inFlight = false
        }
    }

    // Entry-point mode: navigate automatically on auth (no manual back).
    LaunchedEffect(state.isAuthenticated, isEntryPoint) {
        if (isEntryPoint && state.isAuthenticated) onAuthenticated()
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Sign in",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = Mist100
                    )
                },
                navigationIcon = {
                    if (!isEntryPoint) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Mist100
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Mist100,
                    navigationIconContentColor = Mist100
                )
            )
        }
    ) { innerPadding ->
        // V6 — the screen body sits on the same Forest gradient as the
        // splash so the brand is consistent across both surfaces.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(
                    Brush.verticalGradient(colors = listOf(Forest950, Forest900))
                )
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Fade + scale entrance for the brand mark block. The body
            // gets the same treatment so the screen feels intentional.
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(animationSpec = tween(420, easing = LinearEasing)) +
                    scaleIn(
                        initialScale = 0.94f,
                        animationSpec = tween(420, easing = LinearEasing)
                    )
            ) {
                BrandHeader()
            }
            Spacer(Modifier.height(40.dp))

            // Saved accounts section (only if any exist)
            if (state.savedAccounts.isNotEmpty()) {
                Text(
                    text = "Saved accounts",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Mist100,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, bottom = 8.dp)
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(((state.savedAccounts.size * 72) + 8).dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.savedAccounts, key = { it.email }) { session ->
                        SavedAccountRow(
                            session = session,
                            isActive = session.email == state.activeEmail,
                            onTap = {
                                viewModel.switchAccount(session.email) {
                                    onAuthenticated()
                                }
                            },
                            onRemove = {
                                viewModel.removeAccount(session.email)
                            }
                        )
                    }
                }
                Spacer(Modifier.height(28.dp))
            }

            // V6 — primary action (Google) is a filled, full-width
            // button at 56dp with the brand teal. Loading state shows
            // the spinner inline so the user knows the round-trip is
            // in flight.
            Button(
                onClick = {
                    val activity = context as? Activity
                    if (activity == null) {
                        viewModel.setGoogleError("Sign-in requires a foreground screen.")
                        return@Button
                    }
                    scope.launch { launchGoogleSignIn(activity) }
                },
                enabled = !state.isGoogleLoading && !inFlight,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Sage400,
                    contentColor = Forest950
                )
            ) {
                if (state.isGoogleLoading) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp),
                        color = Forest950
                    )
                    Spacer(Modifier.size(12.dp))
                    Text(
                        text = "Connecting…",
                        style = MaterialTheme.typography.titleMedium
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "G",
                            color = Forest950,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    Spacer(Modifier.size(12.dp))
                    Text(
                        text = "Continue with Google",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            if (state.errorMessage != null) {
                Spacer(Modifier.height(16.dp))
                // V6 — error gets its own visual treatment: tonal card
                // with the error-on-container role so the message is
                // never a wall of red text.
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ) {
                    Text(
                        text = state.errorMessage ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = "Your notes are private to you. We never read them.",
                style = MaterialTheme.typography.bodySmall,
                color = Sage400.copy(alpha = 0.6f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

/**
 * SHA-256 hash of [input] encoded as URL-safe base64 (no padding).
 * Matches what Google Identity Services expects for the `nonce`
 * request param.
 */
private fun sha256Base64Url(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return android.util.Base64.encodeToString(
        digest,
        android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
    )
}

@Composable
private fun BrandHeader() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BrandMark(sizeDp = 72)
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Notes",
            style = MaterialTheme.typography.headlineLarge.copy(
                letterSpacing = (-0.5).sp
            ),
            color = Mist100
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Sign in to sync your notes across devices.",
            style = MaterialTheme.typography.bodyMedium,
            color = Sage400.copy(alpha = 0.85f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun SavedAccountRow(
    session: AuthStore.Session,
    isActive: Boolean,
    onTap: () -> Unit,
    onRemove: () -> Unit
) {
    // V6 — saved-account row on the forest-tinted login screen: use
    // a translucent forest tile with a sage avatar so it sits in the
    // brand context without breaking the gradient.
    val container = if (isActive)
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
    else Forest900
    val content = MaterialTheme.colorScheme.onPrimaryContainer
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onTap() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (isActive)
                MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Sage400.copy(alpha = 0.30f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = session.email.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = Mist100
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.email,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = Mist100
                )
                if (isActive) {
                    Text(
                        text = "Active",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        text = "Tap to switch",
                        style = MaterialTheme.typography.bodySmall,
                        color = Sage400.copy(alpha = 0.7f)
                    )
                }
            }
            if (isActive) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Active",
                    tint = MaterialTheme.colorScheme.primary
                )
            } else {
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Remove account",
                        tint = Mist100
                    )
                }
            }
        }
    }
}
