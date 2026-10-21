package com.example.notes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.NoteAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Delete
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
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
    const val NOTE_EDIT = "note_edit/{noteId}"
    const val SETTINGS = "settings"
    const val LOGIN = "login"
    fun noteEdit(noteId: Long?): String = "note_edit/${noteId ?: 0L}"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScreen(repository: NoteRepository, application: NotesApplication) {
    val configuration = LocalConfiguration.current
    // V4.3: restored the original 65% drawer width per design feedback.
    // V5.1 — reduced to 50% because 65% left only a thin scrim zone that
    // mis-triggered the FAB on some devices (Samsung A55 5G reported).
    // 50% keeps the scrim tappable while giving the drawer content more
    // breathing room without crowding it.
    val drawerWidth = (configuration.screenWidthDp * 0.50f).dp

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

    // Issue 3 — Drawer behavior:
    //   - gesturesEnabled = false (swipe to open/close disabled)
    //   - Drawer only opens via the menu button (handled below in AppNavHost)
    //   - Drawer closes on nav-item tap, or on scrim tap (the area to the
    //     right of the drawerContent), or when it's more than 35% visible
    //     and the user taps the outside zone.
    //
    // The scrim region provided by ModalNavigationDrawer already calls
    // drawerState.close() when tapped (this is its built-in behavior).
    // Additionally, every item's onClick explicitly closes the drawer.
    val allNotesCount by repository.noteCount.collectAsStateWithLifecycle(initialValue = 0)
    val favoriteCount by repository.favoriteCount.collectAsStateWithLifecycle(initialValue = 0)
    val trashedCount by repository.trashedCount.collectAsStateWithLifecycle(initialValue = 0)

    if (showNavigationDrawer) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = false,
            // ModalNavigationDrawer's built-in scrim already handles
            // tap-to-close on the outside zone (i.e. it calls
            // drawerState.close() when the area not covered by
            // drawerContent is tapped).
            drawerContent = {
                val context = androidx.compose.ui.platform.LocalContext.current
                val activeEmail = remember {
                    com.example.notes.data.local.AuthStore.getActive(context)?.email ?: "raximjonozod1727@gmail.com"
                }
                ModalDrawerSheet(
                    modifier = Modifier
                        .width(minOf((configuration.screenWidthDp * 0.78f).dp, 320.dp))
                        .fillMaxHeight(),
                    drawerContainerColor = MaterialTheme.colorScheme.surface
                ) {
                    Text(
                        text = "Notes",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp)
                    )

                    // Account Card per V2-10
                    Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 16.dp)
                            .fillMaxWidth()
                    ) {
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
                        ) {
                            androidx.compose.foundation.layout.Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = androidx.compose.ui.Alignment.Center
                            ) {
                                Text(
                                    text = activeEmail.take(1).uppercase(),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = activeEmail,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Synced · just now",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

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
                                navController.navigate(Routes.FAVORITES)
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
                                navController.navigate(Routes.TRASH)
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
                        icon = { Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null) },
                        label = { Text("Help & feedback", style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium) },
                        selected = false,
                        shape = RoundedCornerShape(24.dp),
                        colors = navItemColors,
                        onClick = {
                            scope.launch { drawerState.close() }
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
                scope = scope
            )
        }
    } else {
        AppNavHost(
            navController = navController,
            repository = repository,
            application = application,
            notesListViewModel = notesListViewModel,
            drawerState = drawerState,
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
    scope: CoroutineScope
) {
    NavHost(
        navController = navController,
        startDestination = Routes.SPLASH
    ) {
        composable(Routes.SPLASH) {
            SplashScreen(
                onTimeout = {
                    // Cold-start routing: if we already have an active
                    // session, jump straight into the notes list;
                    // otherwise land on the login screen (the new entry
                    // point for unauthenticated users).
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

        composable(Routes.NOTES_LIST) {
            NotesListScreen(
                viewModel = notesListViewModel,
                filter = NotesFilter.ALL,
                onAddNote = {
                    navController.navigate(Routes.noteEdit(null))
                },
                onNoteClick = { noteId ->
                    navController.navigate(Routes.noteEdit(noteId))
                },
                onMenuClick = {
                    // Menu button toggles the drawer.
                    scope.launch {
                        when {
                            drawerState.isClosed -> drawerState.open()
                            else -> drawerState.close()
                        }
                    }
                }
            )
        }

        composable(Routes.FAVORITES) {
            NotesListScreen(
                viewModel = notesListViewModel,
                filter = NotesFilter.FAVORITES,
                onAddNote = {
                    navController.navigate(Routes.noteEdit(null))
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
                }
            )
        }

        composable(Routes.TRASH) {
            NotesListScreen(
                viewModel = notesListViewModel,
                filter = NotesFilter.TRASH,
                onAddNote = { },
                onNoteClick = { },
                onMenuClick = {
                    scope.launch {
                        when {
                            drawerState.isClosed -> drawerState.open()
                            else -> drawerState.close()
                        }
                    }
                }
            )
        }

        composable(
            route = Routes.NOTE_EDIT,
            arguments = listOf(
                navArgument("noteId") { type = NavType.LongType }
            )
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
            NoteEditScreen(
                noteId = noteId,
                viewModel = noteEditViewModel,
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
                    // All accounts gone → pop back stack to the login entry.
                    navController.popBackStack(Routes.NOTES_LIST, inclusive = true)
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(0) { inclusive = true }
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