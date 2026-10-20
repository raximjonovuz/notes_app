package com.example.notes.ui.auth

import android.app.Activity
import android.app.Application
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NoteAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import com.example.notes.ui.theme.notesHeaderBackground
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
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Sign in",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    if (!isEntryPoint) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = notesHeaderBackground()
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BrandHeader()
            Spacer(Modifier.height(32.dp))

            // Saved accounts section (only if any exist)
            if (state.savedAccounts.isNotEmpty()) {
                Text(
                    text = "Saved accounts",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
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
                Spacer(Modifier.height(24.dp))
            }

            // Single Google sign-in button. Always available.
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
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                if (state.isGoogleLoading) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.size(12.dp))
                    Text("Connecting…", fontSize = 16.sp)
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
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                    Spacer(Modifier.size(12.dp))
                    Text("Continue with Google", fontSize = 16.sp)
                }
            }

            if (state.errorMessage != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = state.errorMessage ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
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
        Icon(
            imageVector = Icons.Filled.NoteAlt,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Notes",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Sign in with Google to sync your notes.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onTap() },
        colors = CardDefaults.cardColors(
            containerColor = if (isActive)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
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
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = session.email.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.email,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                if (isActive) {
                    Text(
                        text = "Active",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
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
                        contentDescription = "Remove account"
                    )
                }
            }
        }
    }
}
