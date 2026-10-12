package com.example.notes.ui.settings

import android.app.Application
import android.net.Uri
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.notes.BuildConfig
import com.example.notes.data.local.AuthStore
import com.example.notes.ui.auth.AuthViewModel
import com.example.notes.ui.theme.ThemeManager
import com.example.notes.ui.theme.notesHeaderBackground
import com.example.notes.util.BackupExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLoginClick: () -> Unit = {},
    /** Called after the active account is removed and no other accounts remain. */
    onRequireLogin: () -> Unit = {}
) {
    var isDark by remember { mutableStateOf(ThemeManager.darkMode) }

    val context = LocalContext.current
    val authViewModel: AuthViewModel = viewModel(
        factory = AuthViewModel.factory(context.applicationContext as Application)
    )
    val authState by authViewModel.state.collectAsStateWithLifecycle()

    // Confirmation dialogs
    var showLogoutAllDialog by remember { mutableStateOf(false) }
    var showLogoutOneDialog by remember { mutableStateOf<String?>(null) }
    // V5.1.5 — backup status feedback rendered right under the button
    var lastBackupStatus by remember { mutableStateOf<String?>(null) }
    val backupSnackbar = remember { SnackbarHostState() }
    val suggestedBackupName = remember { BackupExporter.suggestFileName() }

    // V5.1.5 — Storage Access Framework launcher. Asks the system to
    // create a new JSON file at a path the user picks from the system
    // file picker. When the URI is returned we read every note from the
    // repository and stream the JSON straight to it.
    val scope = rememberCoroutineScope()
    val appContext = context.applicationContext
    val notesDao = remember(appContext) {
        (appContext as com.example.notes.NotesApplication).database.noteDao()
    }
    val exportJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { target: Uri? ->
        if (target == null) return@rememberLauncherForActivityResult
        scope.launch {
            lastBackupStatus = "Exporting…"
            try {
                val backupUserId = com.example.notes.data.local.AuthStore.getActive(appContext)?.userId ?: 0
                val notes = withContext(Dispatchers.IO) { notesDao.getAllNotesSnapshot(backupUserId) }
                val written = withContext(Dispatchers.IO) {
                    BackupExporter.writeTo(appContext, notes, target)
                }
                lastBackupStatus =
                    "Exported $written bytes (${notes.size} notes) to:\n$target"
                backupSnackbar.showSnackbar(
                    message = "Backup saved (${notes.size} notes)",
                    withDismissAction = true
                )
            } catch (t: Throwable) {
                lastBackupStatus = "Backup failed: ${t.message ?: t.javaClass.simpleName}"
                backupSnackbar.showSnackbar(
                    message = "Backup failed",
                    withDismissAction = true
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
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
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
        ) {

        // V5.1.5 — Snackbar host for backup status / errors. Kept
        // outside the inner Column so the bar is anchored at the
        // bottom of the screen regardless of vertical scroll.
        SnackbarHost(hostState = backupSnackbar)
        //
            Text(
                text = "Appearance",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            SettingToggleCard(
                title = "Dark mode",
                subtitle = "Enable dark theme for the entire app",
                checked = isDark,
                onCheckedChange = {
                    isDark = it
                    ThemeManager.setDarkMode(context, it)
                }
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Account",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (authState.savedAccounts.isEmpty()) {
                // Anonymous card
                SettingCard(
                    title = "Not signed in",
                    subtitle = "Sign in to sync your notes across devices."
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onLoginClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null
                    )
                    Text(
                        text = " Sign in",
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            } else {
                // Saved accounts list
                val saved = authState.savedAccounts
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(((saved.size * 64) + 4).dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(saved, key = { it.email }) { session ->
                        AccountRow(
                            session = session,
                            isActive = session.email == authState.activeEmail,
                            onSwitch = {
                                if (session.email != authState.activeEmail) {
                                    authViewModel.switchAccount(session.email)
                                }
                            },
                            onLogout = {
                                showLogoutOneDialog = session.email
                            }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = onLoginClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null
                    )
                    Text(
                        text = " Add account",
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }

                // V4.3: "Log out of all" is always visible when at least one
                // account is saved, not just when there are 2+. Single-account
                // users still need an exit hatch.
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { showLogoutAllDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Log out of all accounts",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // V5.1.5 — backup / data export. Uses the Storage Access
            // Framework so the user can pick any folder visible to
            // the system file picker (Downloads, Drive, SD card…).
            Text(
                text = "Backup",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            SettingCard(
                title = "Export notes to JSON",
                subtitle = "Save a portable backup of every note, " +
                    "including formatting, images, and account info."
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { exportJsonLauncher.launch(suggestedBackupName) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Download, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Export all notes")
            }
            lastBackupStatus?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "About",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            SettingCard(title = "Version", subtitle = BuildConfig.VERSION_NAME)
            Spacer(Modifier.height(8.dp))
            SettingCard(
                title = "Storage",
                subtitle = if (authState.activeEmail != null) "Local + cloud sync" else "Local only"
            )
        }
    }

    // ---- Dialogs ----
    if (showLogoutAllDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutAllDialog = false },
            title = { Text("Log out of all accounts?") },
            text = { Text("You will need to sign in again to sync your notes.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutAllDialog = false
                        authViewModel.logoutAll()
                        onRequireLogin()
                    }
                ) {
                    Text("Log out all")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutAllDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    showLogoutOneDialog?.let { email ->
        val isActive = email == authState.activeEmail
        AlertDialog(
            onDismissRequest = { showLogoutOneDialog = null },
            title = { Text(if (isActive) "Log out?" else "Remove account?") },
            text = {
                Text(
                    if (isActive)
                        "You will be signed out of $email."
                    else
                        "Remove $email from this device. You can sign in again anytime."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutOneDialog = null
                        if (isActive) {
                            // Active sign-out — keep saved session for re-login.
                            authViewModel.logoutActive()
                            onRequireLogin()
                        } else {
                            // Inactive remove — fully delete this saved account.
                            authViewModel.removeAccount(email)
                            if (authState.activeEmail == null) {
                                onRequireLogin()
                            }
                        }
                    }
                ) {
                    Text(if (isActive) "Log out" else "Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutOneDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun AccountRow(
    session: AuthStore.Session,
    isActive: Boolean,
    onSwitch: () -> Unit,
    onLogout: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSwitch() },
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
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = session.email.take(1).uppercase(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Spacer(Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.email,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (isActive) {
                // Active account — show both the "Active" check and a
                // separate logout icon so the user can always sign out
                // without first adding another account.
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Active",
                    tint = MaterialTheme.colorScheme.primary
                )
                IconButton(onClick = onLogout) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Logout,
                        contentDescription = "Log out",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                IconButton(onClick = onLogout) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Logout,
                        contentDescription = "Log out",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingToggleCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

@Composable
private fun SettingCard(title: String, subtitle: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp
            )
        }
    }
}