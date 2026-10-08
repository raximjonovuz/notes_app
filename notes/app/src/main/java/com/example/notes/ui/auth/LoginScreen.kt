package com.example.notes.ui.auth

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.notes.data.local.AuthStore
import com.example.notes.ui.theme.notesHeaderBackground
import com.example.notes.util.Logger
import com.google.android.gms.auth.api.identity.BeginSignInRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException

/**
 * V4.2 — Google-only sign-in screen. The user has explicitly removed the
 * email/password flow; the only way into the app is "Continue with Google".
 *
 * Layout:
 *   - Brand header
 *   - If saved accounts exist: list of those (tap to switch)
 *   - "Continue with Google" button (primary action)
 *
 * @param isEntryPoint when true the back button is hidden (we are the
 *        first screen) and auto-navigation happens on auth.
 * @param onAuthenticated called once after a successful auth round-trip.
 * @param onBack          up-navigation when the user taps the back arrow.
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

    // V4.2 — Google Sign-In launcher. The screen owns the launcher
    // because ActivityResult APIs are activity-scoped. The ID token
    // is forwarded to the ViewModel, which handles the backend swap.
    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        Logger.d(
            "LoginScreen",
            "GoogleSignIn launcher result: code=${result.resultCode}, intent=${result.data}"
        )
        val credential = try {
            Identity.getSignInClient(context)
                .getSignInCredentialFromIntent(result.data)
        } catch (e: ApiException) {
            Logger.w("LoginScreen", "getSignInCredential failed", e)
            viewModel.clearGoogleError()
            return@rememberLauncherForActivityResult
        }
        val idToken = credential?.googleIdToken
        Logger.d(
            "LoginScreen",
            "GoogleSignIn idToken=${idToken?.take(20)}..."
        )
        idToken?.let { viewModel.submitGoogle(it) } ?: viewModel.clearGoogleError()
    }

    fun launchGoogleSignIn() {
        val client = Identity.getSignInClient(context)
        val webClientId = "506282696730-jmrbedtsp0ce2onj5q5uo5hcg8k89lo6.apps.googleusercontent.com"
        val request = BeginSignInRequest.builder()
            .setGoogleIdTokenRequestOptions(
                BeginSignInRequest.GoogleIdTokenRequestOptions.builder()
                    .setSupported(true)
                    .setServerClientId(webClientId)
                    .setFilterByAuthorizedAccounts(false)
                    .build()
            )
            .setAutoSelectEnabled(true)
            .build()
        Logger.d("LoginScreen", "beginSignIn starting with webClientId=$webClientId")
        client.beginSignIn(request)
            .addOnSuccessListener { result ->
                Logger.d("LoginScreen", "beginSignIn succeeded, launching IntentSender")
                val senderRequest = androidx.activity.result.IntentSenderRequest.Builder(
                    result.pendingIntent.intentSender
                ).build()
                googleSignInLauncher.launch(senderRequest)
            }
            .addOnFailureListener { e ->
                Logger.w("LoginScreen", "beginSignIn failed", e)
                viewModel.clearGoogleError()
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
                onClick = { launchGoogleSignIn() },
                enabled = !state.isGoogleLoading,
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
                    // Inline "G" mark (avoids an extra drawable dependency)
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
