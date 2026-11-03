package com.example.notes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.NoteAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.notes.data.repository.NoteRepository
import com.example.notes.ui.auth.LoginScreen
import com.example.notes.ui.edit.NoteEditScreen
import com.example.notes.ui.edit.NoteEditViewModel
import com.example.notes.ui.notes.NotesFilter
import com.example.notes.ui.notes.NotesListScreen
import com.example.notes.ui.notes.NotesListViewModel
import com.example.notes.ui.settings.SettingsScreen
import com.example.notes.ui.splash.SplashScreen
import com.example.notes.ui.theme.NotesTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Route names for the single-Activity navigation graph. Kept private to
 * [MainActivity] so the rest of the app only sees typed entry points.
 */
private object Routes {
    const val SPLASH = "splash"
    const val NOTES_LIST = "notes_list"
    const val FAVORITES = "favorites"
    const val TRASH = "trash"
    const val NOTE_EDIT = "note_edit/{noteId}?checklist={checklist}&imageUri={imageUri}"
    const val SETTINGS = "settings"
    const val LOGIN = "login"
    fun noteEdit(noteId: Long?, isChecklist: Boolean = false, imageUri: String? = null): String {
        val id = noteId ?: 0L
        val encodedImage = if (!imageUri.isNullOrBlank()) java.net.URLEncoder.encode(imageUri, "UTF-8") else ""
        return "note_edit/$id?checklist=$isChecklist&imageUri=$encodedImage"
    }
}

class MainActivity : ComponentActivity() {
    private var appUpdateManager: AppUpdateManager? = null

    private val updateLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) {
            // Update flow completed or cancelled
        }
    }

    private val installListener = InstallStateUpdatedListener { state ->
        if (state.installStatus() == InstallStatus.DOWNLOADED) {
            Toast.makeText(
                this,
                "Yangilanish yuklab olindi! Ilova yangilanmoqda...",
                Toast.LENGTH_LONG
            ).show()
            appUpdateManager?.completeUpdate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        checkForAppUpdate()
        requestNotificationPermission()

        val application = applicationContext as NotesApplication

        setContent {
            NotesTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppScreen(
                        repository = application.repository,
                        application = application
                    )
                }
            }
        }
    }

    private fun checkForAppUpdate() {
        try {
            val manager = AppUpdateManagerFactory.create(this)
            appUpdateManager = manager
            manager.registerListener(installListener)

            val appUpdateInfoTask = manager.appUpdateInfo
            appUpdateInfoTask.addOnSuccessListener { appUpdateInfo ->
                if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                    && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
                ) {
                    manager.startUpdateFlowForResult(
                        appUpdateInfo,
                        updateLauncher,
                        AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build()
                    )
                }
            }
        } catch (_: Exception) {
            // Ignore if Play Store is absent or offline
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            appUpdateManager?.appUpdateInfo?.addOnSuccessListener { appUpdateInfo ->
                if (appUpdateInfo.installStatus() == InstallStatus.DOWNLOADED) {
                    appUpdateManager?.completeUpdate()
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            appUpdateManager?.unregisterListener(installListener)
        } catch (_: Exception) {
        }
    }
    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1002)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScreen(repository: NoteRepository, application: NotesApplication) {
    val configuration = LocalConfiguration.current
    // 60% drawer width, leaving 40% outside zone for tap-to-dismiss
    val drawerWidth = (configuration.screenWidthDp * 0.60f).dp

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val navController = rememberNavController()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route

    val notesListViewModel: NotesListViewModel = viewModel(
        factory = NotesListViewModel.Factory(repository, application)
    )

    LaunchedEffect(currentRoute) {
        notesListViewModel.setFilter(
            when (currentRoute) {
                Routes.FAVORITES -> NotesFilter.FAVORITES
                Routes.TRASH -> NotesFilter.TRASH
                else -> NotesFilter.ALL
            }
        )
    }

    // Only show the navigation drawer on the notes-list routes (NOTES_LIST,
    // FAVORITES, TRASH). On other screens the drawer is not rendered at all.
    val drawerRoutes = setOf(Routes.NOTES_LIST, Routes.FAVORITES, Routes.TRASH)
    val showNavigationDrawer = currentRoute in drawerRoutes

    // Make sure the drawer is closed any time the user navigates away from
    // a drawer-eligible route (e.g. after tapping "Settings"). Without this
    // it could stay half-open across navigation transitions.
    LaunchedEffect(currentRoute) {
        if (currentRoute !in drawerRoutes && !drawerState.isClosed) {
            drawerState.close()
        }
    }

    val isDrawerOpen = drawerState.isOpen || drawerState.targetValue == DrawerValue.Open

    // Close drawer on system back press when drawer is open
    BackHandler(enabled = isDrawerOpen) {
        scope.launch { drawerState.close() }
    }

    val allNotesCount by repository.noteCount.collectAsStateWithLifecycle(initialValue = 0)
    val favoriteCount by repository.favoriteCount.collectAsStateWithLifecycle(initialValue = 0)
    val trashedCount by repository.trashedCount.collectAsStateWithLifecycle(initialValue = 0)
    var showLogoutConfirmDialog by remember { mutableStateOf(false) }

    if (showNavigationDrawer) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = isDrawerOpen,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier
                        .width(drawerWidth)
                        .fillMaxHeight(),
                    drawerContainerColor = MaterialTheme.colorScheme.surface
                ) {
                    Text(
                        text = "Notes",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp)
                    )

                    val navItemColors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )

                    NavigationDrawerItem(
                        icon = { Icon(Icons.Filled.NoteAlt, contentDescription = null) },
                        label = { Text("All Notes", style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium) },
                        badge = {
                            DrawerBadge(count = allNotesCount, isSelected = currentRoute == Routes.NOTES_LIST)
                        },
                        selected = currentRoute == Routes.NOTES_LIST,
                        shape = RoundedCornerShape(24.dp),
                        colors = navItemColors,
                        onClick = {
                            scope.launch { drawerState.close() }
                            if (currentRoute != Routes.NOTES_LIST) {
                                navController.navigate(Routes.NOTES_LIST) {
                                    popUpTo(Routes.NOTES_LIST) { inclusive = true }
                                }
                            }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                    )

                    NavigationDrawerItem(
                        icon = { Icon(Icons.Filled.Star, contentDescription = null) },
                        label = { Text("Favorites", style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium) },
                        badge = {
                            if (favoriteCount > 0) {
                                DrawerBadge(count = favoriteCount, isSelected = currentRoute == Routes.FAVORITES)
                            }
                        },
                        selected = currentRoute == Routes.FAVORITES,
                        shape = RoundedCornerShape(24.dp),
                        colors = navItemColors,
                        onClick = {
                            scope.launch { drawerState.close() }
                            if (currentRoute != Routes.FAVORITES) {
                                navController.navigate(Routes.FAVORITES) {
                                    popUpTo(Routes.NOTES_LIST) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                    )

                    NavigationDrawerItem(
                        icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                        label = { Text("Trash", style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium) },
                        badge = {
                            if (trashedCount > 0) {
                                DrawerBadge(count = trashedCount, isSelected = currentRoute == Routes.TRASH)
                            }
                        },
                        selected = currentRoute == Routes.TRASH,
                        shape = RoundedCornerShape(24.dp),
                        colors = navItemColors,
                        onClick = {
                            scope.launch { drawerState.close() }
                            if (currentRoute != Routes.TRASH) {
                                navController.navigate(Routes.TRASH) {
                                    popUpTo(Routes.NOTES_LIST) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    androidx.compose.material3.HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    NavigationDrawerItem(
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text("Settings", style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium) },
                        selected = currentRoute == Routes.SETTINGS,
                        shape = RoundedCornerShape(24.dp),
                        colors = navItemColors,
                        onClick = {
                            scope.launch { drawerState.close() }
                            if (currentRoute != Routes.SETTINGS) {
                                navController.navigate(Routes.SETTINGS)
                            }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                    )

                    NavigationDrawerItem(
                        icon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        label = { Text("Log out", style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = MaterialTheme.colorScheme.error) },
                        selected = false,
                        shape = RoundedCornerShape(24.dp),
                        colors = navItemColors,
                        onClick = {
                            scope.launch { drawerState.close() }
                            showLogoutConfirmDialog = true
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                    )
                }
            }
        ) {
            AppNavHost(
                navController = navController,
                repository = repository,
                application = application,
                notesListViewModel = notesListViewModel,
                drawerState = drawerState,
                isDrawerOpen = isDrawerOpen,
                scope = scope
            )
        }

        if (showLogoutConfirmDialog) {
            val context = androidx.compose.ui.platform.LocalContext.current
            val activeEmail = com.example.notes.data.local.AuthStore.getActive(context)?.email ?: ""
            AlertDialog(
                onDismissRequest = { showLogoutConfirmDialog = false },
                title = { Text("Log out?") },
                text = { Text(if (activeEmail.isNotBlank()) "You will be signed out of $activeEmail." else "Are you sure you want to log out?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showLogoutConfirmDialog = false
                            scope.launch {
                                drawerState.close()
                                com.example.notes.data.remote.AuthRepository(context).clearAll()
                                navController.navigate(Routes.LOGIN) {
                                    popUpTo(navController.graph.id) { inclusive = true }
                                }
                            }
                        }
                    ) {
                        Text("Log out", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showLogoutConfirmDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    } else {
        AppNavHost(
            navController = navController,
            repository = repository,
            application = application,
            notesListViewModel = notesListViewModel,
            drawerState = drawerState,
            isDrawerOpen = isDrawerOpen,
            scope = scope
        )
    }
}

@Composable
private fun AppNavHost(
    navController: NavHostController,
    repository: NoteRepository,
    application: NotesApplication,
    notesListViewModel: NotesListViewModel,
    drawerState: DrawerState,
    isDrawerOpen: Boolean,
    scope: CoroutineScope
) {
    val handleLogout: () -> Unit = {
        scope.launch {
            drawerState.close()
            com.example.notes.data.remote.AuthRepository(application).clearAll()
            navController.navigate(Routes.LOGIN) {
                popUpTo(navController.graph.id) { inclusive = true }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.SPLASH,
        enterTransition = {
            fadeIn(animationSpec = tween(260)) + slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(260)
            )
        },
        exitTransition = {
            fadeOut(animationSpec = tween(220)) + slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(220)
            )
        },
        popEnterTransition = {
            fadeIn(animationSpec = tween(260)) + slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(260)
            )
        },
        popExitTransition = {
            fadeOut(animationSpec = tween(220)) + slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(220)
            )
        }
    ) {
        composable(
            route = Routes.SPLASH,
            exitTransition = {
                fadeOut(animationSpec = tween(180))
            }
        ) {
            SplashScreen(
                onTimeout = {
                    val hasActiveSession =
                        com.example.notes.data.local.AuthStore.getActive(application) != null
                    val destination =
                        if (hasActiveSession) Routes.NOTES_LIST else Routes.LOGIN
                    navController.navigate(destination) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Routes.NOTES_LIST,
            enterTransition = {
                if (initialState.destination.route == Routes.SPLASH) {
                    fadeIn(animationSpec = tween(220))
                } else {
                    fadeIn(animationSpec = tween(260)) + slideIntoContainer(
                        AnimatedContentTransitionScope.SlideDirection.Start,
                        animationSpec = tween(260)
                    )
                }
            }
        ) {
            NotesListScreen(
                viewModel = notesListViewModel,
                filter = NotesFilter.ALL,
                isDrawerOpen = isDrawerOpen,
                onAddNote = {
                    navController.navigate(Routes.noteEdit(null))
                },
                onAddChecklistNote = {
                    navController.navigate(Routes.noteEdit(null, isChecklist = true))
                },
                onAddNoteWithImage = { uri ->
                    navController.navigate(Routes.noteEdit(null, imageUri = uri))
                },
                onNoteClick = { noteId ->
                    navController.navigate(Routes.noteEdit(noteId))
                },
                onMenuClick = {
                    scope.launch {
                        when {
                            drawerState.isClosed -> drawerState.open()
                            else -> drawerState.close()
                        }
                    }
                },
                onLogout = handleLogout
            )
        }

        composable(Routes.FAVORITES) {
            NotesListScreen(
                viewModel = notesListViewModel,
                filter = NotesFilter.FAVORITES,
                isDrawerOpen = isDrawerOpen,
                onAddNote = {
                    navController.navigate(Routes.noteEdit(null))
                },
                onAddChecklistNote = {
                    navController.navigate(Routes.noteEdit(null, isChecklist = true))
                },
                onAddNoteWithImage = { uri ->
                    navController.navigate(Routes.noteEdit(null, imageUri = uri))
                },
                onNoteClick = { noteId ->
                    navController.navigate(Routes.noteEdit(noteId))
                },
                onMenuClick = {
                    scope.launch {
                        when {
                            drawerState.isClosed -> drawerState.open()
                            else -> drawerState.close()
                        }
                    }
                },
                onLogout = handleLogout
            )
        }

        composable(Routes.TRASH) {
            NotesListScreen(
                viewModel = notesListViewModel,
                filter = NotesFilter.TRASH,
                isDrawerOpen = isDrawerOpen,
                onAddNote = { },
                onAddChecklistNote = { },
                onAddNoteWithImage = { },
                onNoteClick = { },
                onMenuClick = {
                    scope.launch {
                        when {
                            drawerState.isClosed -> drawerState.open()
                            else -> drawerState.close()
                        }
                    }
                },
                onLogout = handleLogout
            )
        }

        composable(
            route = Routes.NOTE_EDIT,
            arguments = listOf(
                navArgument("noteId") { type = NavType.LongType },
                navArgument("checklist") {
                    type = NavType.BoolType
                    defaultValue = false
                },
                navArgument("imageUri") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            ),
            enterTransition = {
                // Google Keep style: Elegant container expand from center-scale with cubic-bezier
                androidx.compose.animation.fadeIn(
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = 280,
                        easing = androidx.compose.animation.core.FastOutSlowInEasing
                    )
                ) + androidx.compose.animation.scaleIn(
                    initialScale = 0.88f,
                    animationSpec = androidx.compose.animation.core.spring(
                        dampingRatio = androidx.compose.animation.core.Spring.DampingRatioLowBouncy,
                        stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
                    )
                )
            },
            exitTransition = {
                // Graceful shrink back into the list
                androidx.compose.animation.fadeOut(
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = 220,
                        easing = androidx.compose.animation.core.FastOutLinearInEasing
                    )
                ) + androidx.compose.animation.scaleOut(
                    targetScale = 0.90f,
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = 220,
                        easing = androidx.compose.animation.core.FastOutLinearInEasing
                    )
                )
            },
            popEnterTransition = {
                androidx.compose.animation.fadeIn(
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = 240,
                        easing = androidx.compose.animation.core.LinearOutSlowInEasing
                    )
                ) + androidx.compose.animation.scaleIn(
                    initialScale = 0.92f,
                    animationSpec = androidx.compose.animation.core.spring(
                        dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
                        stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
                    )
                )
            },
            popExitTransition = {
                // On back: smoothly collapse back down into the card location
                androidx.compose.animation.fadeOut(
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = 220,
                        easing = androidx.compose.animation.core.FastOutLinearInEasing
                    )
                ) + androidx.compose.animation.scaleOut(
                    targetScale = 0.88f,
                    animationSpec = androidx.compose.animation.core.spring(
                        dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
                        stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
                    )
                )
            }
        ) { backStackEntry ->
            val noteEditViewModel: NoteEditViewModel = viewModel(
                factory = NoteEditViewModel.Factory(
                    repository = repository,
                    syncRepository = (application as com.example.notes.NotesApplication).syncRepository,
                    // V5.1 — pass the application-scoped CoroutineScope so
                    // saveNow() / flushPendingPush() survive navigation
                    // and the user's last keystroke is never dropped.
                    applicationScope = (application as com.example.notes.NotesApplication).applicationScope,
                    appContext = application.applicationContext
                ),
                key = "note_edit_${backStackEntry.arguments?.getLong("noteId")}"
            )
            val noteId = backStackEntry.arguments?.getLong("noteId")?.takeIf { it != 0L }
            val isChecklist = backStackEntry.arguments?.getBoolean("checklist") ?: false
            val rawImageUri = backStackEntry.arguments?.getString("imageUri")?.takeIf { it.isNotBlank() }
            val imageUri = if (rawImageUri != null) {
                try {
                    java.net.URLDecoder.decode(rawImageUri, "UTF-8")
                } catch (_: Exception) {
                    rawImageUri
                }
            } else null

            NoteEditScreen(
                noteId = noteId,
                viewModel = noteEditViewModel,
                startAsChecklist = isChecklist,
                initialImageUri = imageUri,
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = {
                    navController.popBackStack()
                },
                onLoginClick = {
                    navController.navigate(Routes.LOGIN)
                },
                onRequireLogin = {
                    // All accounts gone → pop back stack and navigate to login.
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(navController.graph.id) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.LOGIN) { entry ->
            // LoginScreen is the app entry-point when the previous route in
            // the back stack is either the Splash screen (cold-start path)
            // or there is no previous entry at all. In that mode we hide
            // the back arrow and replace the stack with the notes list on
            // successful auth. From Settings we just pop back so the user
            // returns to the account manager.
            val cameFromSettings =
                entry.savedStateHandle.contains("from_settings") ||
                    navController.previousBackStackEntry?.destination?.route == Routes.SETTINGS
            LoginScreen(
                isEntryPoint = !cameFromSettings,
                onAuthenticated = {
                    if (cameFromSettings) {
                        navController.popBackStack()
                    } else {
                        navController.navigate(Routes.NOTES_LIST) {
                            popUpTo(Routes.LOGIN) { inclusive = true }
                        }
                    }
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}

@Composable
private fun DrawerBadge(count: Int, isSelected: Boolean) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            ),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}