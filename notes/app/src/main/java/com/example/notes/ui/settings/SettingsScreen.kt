package com.example.notes.ui.settings

import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.notes.BuildConfig
import com.example.notes.data.local.AuthStore
import com.example.notes.ui.auth.AuthViewModel
import com.example.notes.ui.theme.ThemeManager
import com.example.notes.ui.theme.ThemeMode
import com.example.notes.ui.theme.notesHeaderBackground
import com.example.notes.util.BackupExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* =====================================================================
 *  V6 — Theme redesign · Settings
 *
 *  Restructured into M3 "grouped settings" sections: a small
 *  `labelLarge` header in the primary color, followed by a rounded
 *  `surfaceContainer` card that holds the rows. Every row has a
 *  leading icon in a tinted circle, a title, an optional subtitle and
 *  a trailing affordance (switch / value / chevron).
 *
 *  New: a System / Light / Dark theme selector (segmented buttons,
 *  default = System) and an optional "use device colors" switch that
 *  is only offered on Android 12+ (Material You). Dynamic color is
 *  OFF by default so the Deep Forest brand wins.
 * ===================================================================== */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLoginClick: () -> Unit = {},
    /** Called after the active account is removed and no other accounts remain. */
    onRequireLogin: () -> Unit = {}
) {
    var selectedMode by remember { mutableStateOf(ThemeManager.themeMode) }
    var dynamicOn by remember { mutableStateOf(ThemeManager.dynamicColorEnabled) }

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
                    containerColor = notesHeaderBackground(),
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        snackbarHost = { SnackbarHost(hostState = backupSnackbar) },
        containerColor = notesHeaderBackground()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            /* ---------------- Appearance ---------------- */
            SectionHeader(title = "Appearance")

            SettingsGroup {
                // V6 — theme mode picker. Segmented buttons give a
                // clear three-way choice with the active option filled
                // in the brand color.
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SettingsIconBadge(icon = Icons.Filled.Palette)
                        Spacer(Modifier.size(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Theme",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = when (selectedMode) {
                                    ThemeMode.SYSTEM -> "Follow the system setting"
                                    ThemeMode.LIGHT -> "Always light"
                                    ThemeMode.DARK -> "Always dark"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val options = listOf(
                            ThemeMode.SYSTEM to "System",
                            ThemeMode.LIGHT to "Light",
                            ThemeMode.DARK to "Dark"
                        )
                        options.forEachIndexed { index, (mode, label) ->
                            SegmentedButton(
                                selected = selectedMode == mode,
                                onClick = {
                                    selectedMode = mode
                                    ThemeManager.setMode(context, mode)
                                },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = options.size
                                )
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }

                // V6 — dynamic color is Android 12+ only, so the switch
                // is hidden entirely on older devices rather than
                // showing a dead control.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    SettingsSwitchRow(
                        icon = Icons.Filled.PhoneAndroid,
                        title = "Use device colors",
                        subtitle = "Material You palette (Android 12+)",
                        checked = dynamicOn,
                        onCheckedChange = {
                            dynamicOn = it
                            ThemeManager.setDynamicColor(context, it)
                        }
                    )
                }
            }

            /* ---------------- Account ---------------- */
            SectionHeader(title = "Account")

            SettingsGroup {
                if (authState.savedAccounts.isEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SettingsIconBadge(icon = Icons.Filled.Person)
                        Spacer(Modifier.size(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Not signed in",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Sign in to sync your notes across devices.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    SettingsGroupAction(
                        label = "Sign in",
                        icon = Icons.Filled.Add,
                        onClick = onLoginClick
                    )
                } else {
                    authState.savedAccounts.forEachIndexed { index, session ->
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 72.dp, end = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
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
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    SettingsGroupAction(
                        label = "Add account",
                        icon = Icons.Filled.Add,
                        onClick = onLoginClick
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    SettingsGroupAction(
                        label = "Log out of all accounts",
                        icon = Icons.AutoMirrored.Filled.Logout,
                        tint = MaterialTheme.colorScheme.error,
                        onClick = { showLogoutAllDialog = true }
                    )
                }
            }

            /* ---------------- Backup ---------------- */
            SectionHeader(title = "Backup")

            SettingsGroup {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { exportJsonLauncher.launch(suggestedBackupName) }
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingsIconBadge(icon = Icons.Filled.CloudUpload)
                    Spacer(Modifier.size(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Export notes to JSON",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Save a portable backup of every note, " +
                                "including formatting, images, and account info.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                lastBackupStatus?.let { msg ->
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }

            /* ---------------- About ---------------- */
            SectionHeader(title = "About")

            SettingsGroup {
                InfoRow(
                    icon = Icons.Filled.Info,
                    title = "Version",
                    value = BuildConfig.VERSION_NAME
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                InfoRow(
                    icon = Icons.Filled.Storage,
                    title = "Storage",
                    value = if (authState.activeEmail != null) "Local + cloud sync"
                    else "Local only"
                )
            }
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

/* =====================================================================
 *  Building blocks
 * ===================================================================== */

/** Small uppercase-ish section label, M3 style. */
@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp)
    )
}

/** Rounded container that groups a set of settings rows. */
@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column { content() }
    }
}

/** Tinted circle holding a leading icon. 40dp visual, 48dp row height. */
@Composable
private fun SettingsIconBadge(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** Row with a leading icon, title, subtitle and a trailing switch. */
@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsIconBadge(icon = icon)
        Spacer(Modifier.size(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.size(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

/** Row with a leading icon, a title and a trailing value string. */
@Composable
private fun InfoRow(
    icon: ImageVector,
    title: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsIconBadge(icon = icon)
        Spacer(Modifier.size(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Full-width tappable action row inside a group (no leading badge). */
@Composable
private fun SettingsGroupAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.size(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = tint
        )
    }
}

/* =====================================================================
 *  Account row
 * ===================================================================== */

@Composable
private fun AccountRow(
    session: AuthStore.Session,
    isActive: Boolean,
    onSwitch: () -> Unit,
    onLogout: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSwitch() }
            .heightIn(min = 56.dp)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    if (isActive)
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.secondaryContainer
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = session.email.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = if (isActive)
                    MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
        Spacer(Modifier.size(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.email,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Text(
                text = if (isActive) "Active" else "Tap to switch",
                style = MaterialTheme.typography.bodySmall,
                color = if (isActive)
                    MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isActive) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Active",
                tint = MaterialTheme.colorScheme.primary
            )
        }
        IconButton(onClick = onLogout) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Logout,
                contentDescription = "Log out",
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}
