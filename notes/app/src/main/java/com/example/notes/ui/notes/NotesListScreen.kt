package com.example.notes.ui.notes

import android.content.Context
import androidx.compose.material.icons.outlined.History
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.notes.data.local.NoteEntity
import com.example.notes.ui.edit.blocksToPlainText
import com.example.notes.ui.edit.parseBlocksFromContent
import com.example.notes.ui.theme.noteCardColor
import com.example.notes.ui.theme.noteCardOnColor
import com.example.notes.ui.theme.notesHeaderBackground
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ViewMode { LINEAR, GRID }

enum class SearchTimeFilter(val label: String) {
    ALL("All"),
    TODAY("Today"),
    THIS_WEEK("This week"),
    HAS_IMAGE("Has image")
}

object SearchHistoryManager {
    private const val PREFS_NAME = "notes_search_history"
    private const val KEY_HISTORY = "recent_queries"
    private const val MAX_HISTORY = 10

    fun getHistory(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return raw.split("|||").filter { it.isNotBlank() }
    }

    fun addQuery(context: Context, query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        val current = getHistory(context).toMutableList()
        current.remove(trimmed)
        current.add(0, trimmed)
        val trimmedList = current.take(MAX_HISTORY)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_HISTORY, trimmedList.joinToString("|||")).apply()
    }

    fun removeQuery(context: Context, query: String) {
        val current = getHistory(context).toMutableList()
        current.remove(query.trim())
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_HISTORY, current.joinToString("|||")).apply()
    }

    fun clearAll(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_HISTORY).apply()
    }
}

/* =====================================================================
 *  Screen
 * ===================================================================== */

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NotesListScreen(
    viewModel: NotesListViewModel,
    filter: NotesFilter,
    onAddNote: () -> Unit,
    onNoteClick: (Long) -> Unit,
    onMenuClick: () -> Unit,
    onLogout: () -> Unit = {}
) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val noteCount by viewModel.noteCount.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()

    val context = androidx.compose.ui.platform.LocalContext.current
    var showOfflineBanner by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshActiveUser()
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val net = cm?.activeNetwork
        val caps = cm?.getNetworkCapabilities(net)
        val isOnline = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        if (!isOnline) {
            showOfflineBanner = true
            kotlinx.coroutines.delay(1200)
            showOfflineBanner = false
        }
    }

    val activeEmail = remember {
        com.example.notes.data.local.AuthStore.getActive(context)?.email ?: ""
    }
    val avatarLetter = activeEmail.take(1).uppercase().ifEmpty { "O" }
    var showAccountDialog by remember { mutableStateOf(false) }

    var isSearchActive by remember { mutableStateOf(false) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var viewMode by remember { mutableStateOf(ViewMode.GRID) }
    var showSortViewSheet by remember { mutableStateOf(false) }
    var longPressedNoteId by remember { mutableStateOf<Long?>(null) }
    var searchTimeFilter by remember { mutableStateOf(SearchTimeFilter.ALL) }
    var searchHistory by remember { mutableStateOf(SearchHistoryManager.getHistory(context)) }

    fun onPerformSearch(q: String) {
        viewModel.onSearchQueryChange(q)
        if (q.isNotBlank()) {
            SearchHistoryManager.addQuery(context, q)
            searchHistory = SearchHistoryManager.getHistory(context)
        }
    }

    val searchFocusRequester = remember { FocusRequester() }
    val linearListState = rememberLazyListState()
    val staggeredGridState = rememberLazyStaggeredGridState()

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) searchFocusRequester.requestFocus()
    }

    fun toggleSelection(noteId: Long) {
        selectedIds = if (selectedIds.contains(noteId)) selectedIds - noteId
        else selectedIds + noteId
        if (selectedIds.isEmpty()) isSelectionMode = false
    }

    val titleText = when (filter) {
        NotesFilter.ALL -> "All Notes"
        NotesFilter.FAVORITES -> "Favorites"
        NotesFilter.TRASH -> "Trash"
    }

    Scaffold(
        topBar = {
            if (isSelectionMode) {
                TopAppBar(
                    title = {
                        Text(
                            text = "${selectedIds.size}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            isSelectionMode = false
                            selectedIds = emptySet()
                        }) {
                            Icon(Icons.Filled.Close, contentDescription = "Exit selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            selectedIds.forEach { id ->
                                val note = notes.find { it.id == id }
                                if (note != null) viewModel.togglePin(note)
                            }
                            isSelectionMode = false
                            selectedIds = emptySet()
                        }) {
                            Icon(Icons.Filled.PushPin, contentDescription = "Pin")
                        }
                        IconButton(onClick = {
                            selectedIds.forEach { id ->
                                val note = notes.find { it.id == id }
                                if (note != null) viewModel.toggleFavorite(note)
                            }
                            isSelectionMode = false
                            selectedIds = emptySet()
                        }) {
                            Icon(Icons.Filled.Star, contentDescription = "Favorite")
                        }
                        IconButton(onClick = {
                            selectedIds.forEach { id ->
                                val note = notes.find { it.id == id }
                                if (note != null) {
                                    if (filter == NotesFilter.TRASH) viewModel.deleteNote(note)
                                    else viewModel.moveToTrash(note)
                                }
                            }
                            isSelectionMode = false
                            selectedIds = emptySet()
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                        }
                        val allSelected = selectedIds.size == notes.size
                        TextButton(onClick = {
                            selectedIds = if (allSelected) emptySet() else notes.map { it.id }.toSet()
                        }) {
                            Text(
                                text = if (allSelected) "Deselect" else "All",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            } else if (isSearchActive) {
                TopAppBar(
                    title = {
                        SearchPillField(
                            query = searchQuery,
                            onQueryChange = { onPerformSearch(it) },
                            focusRequester = searchFocusRequester,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            isSearchActive = false
                            viewModel.onSearchQueryChange("")
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        tonalElevation = 2.dp,
                        shadowElevation = 1.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onMenuClick) {
                                Icon(Icons.Filled.Menu, contentDescription = "Menu")
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { isSearchActive = true }
                                    .padding(horizontal = 8.dp)
                            ) {
                                Text(
                                    text = if (filter == NotesFilter.TRASH) "Search in Trash"
                                    else if (filter == NotesFilter.FAVORITES) "Search in Favorites"
                                    else "Search your notes",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = {
                                viewMode = if (viewMode == ViewMode.GRID) ViewMode.LINEAR else ViewMode.GRID
                            }) {
                                Icon(
                                    imageVector = if (viewMode == ViewMode.GRID) Icons.Filled.ViewAgenda else Icons.Filled.GridView,
                                    contentDescription = "Toggle Grid/List view"
                                )
                            }
                            IconButton(onClick = { showAccountDialog = true }) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = avatarLetter,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (!isSelectionMode && filter == NotesFilter.TRASH && notes.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = {
                        notes.forEach { viewModel.restoreFromTrash(it) }
                    },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Restore,
                            contentDescription = null
                        )
                    },
                    text = {
                        Text(
                            text = "Restore all",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    shape = RoundedCornerShape(20.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.navigationBarsPadding()
                )
            } else if (!isSelectionMode && filter != NotesFilter.TRASH) {
                FloatingActionButton(
                    onClick = onAddNote,
                    shape = RoundedCornerShape(18.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .navigationBarsPadding()
                        .size(60.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "New note",
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        },
        floatingActionButtonPosition = if (filter == NotesFilter.TRASH) FabPosition.Center else FabPosition.End,
        bottomBar = {
            if (isSelectionMode) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding(),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SelectionActionButton(
                            icon = Icons.AutoMirrored.Filled.DriveFileMove,
                            label = "Move",
                            enabled = selectedIds.isNotEmpty(),
                            onClick = {
                                selectedIds.forEach { id ->
                                    val note = notes.find { it.id == id }
                                    if (note != null) viewModel.togglePin(note)
                                }
                                isSelectionMode = false
                                selectedIds = emptySet()
                            }
                        )
                        SelectionActionButton(
                            icon = Icons.Filled.Star,
                            label = "Favorite",
                            enabled = selectedIds.isNotEmpty(),
                            onClick = {
                                selectedIds.forEach { id ->
                                    val note = notes.find { it.id == id }
                                    if (note != null) viewModel.toggleFavorite(note)
                                }
                                isSelectionMode = false
                                selectedIds = emptySet()
                            }
                        )
                        SelectionActionButton(
                            icon = Icons.Filled.Delete,
                            label = "Delete",
                            tint = MaterialTheme.colorScheme.error,
                            enabled = selectedIds.isNotEmpty(),
                            onClick = {
                                selectedIds.forEach { id ->
                                    val note = notes.find { it.id == id }
                                    if (note != null) {
                                        if (filter == NotesFilter.TRASH) viewModel.deleteNote(note)
                                        else viewModel.moveToTrash(note)
                                    }
                                }
                                isSelectionMode = false
                                selectedIds = emptySet()
                            }
                        )
                        SelectionActionButton(
                            icon = Icons.Filled.Share,
                            label = "Share",
                            enabled = selectedIds.isNotEmpty(),
                            onClick = {
                                val selectedNotes = notes.filter { selectedIds.contains(it.id) }
                                val shareText = selectedNotes.joinToString("\n\n---\n\n") { n ->
                                    val plain = blocksToPlainText(parseBlocksFromContent(n.content))
                                    "${n.title}\n$plain"
                                }
                                val sendIntent = android.content.Intent().apply {
                                    action = android.content.Intent.ACTION_SEND
                                    putExtra(android.content.Intent.EXTRA_TEXT, shareText)
                                    type = "text/plain"
                                }
                                context.startActivity(android.content.Intent.createChooser(sendIntent, "Share notes"))
                                isSelectionMode = false
                                selectedIds = emptySet()
                            }
                        )
                    }
                }
            }
        },
        containerColor = notesHeaderBackground()
    ) { innerPadding ->
        val now = remember { System.currentTimeMillis() }
        val todayMidnight = remember(now) {
            val cal = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            cal.timeInMillis
        }
        val weekStart = remember(todayMidnight) { todayMidnight - 7L * 24 * 3600 * 1000 }

        val filteredNotes: List<NoteEntity> = remember(notes, isSearchActive, searchTimeFilter) {
            if (!isSearchActive) notes
            else {
                when (searchTimeFilter) {
                    SearchTimeFilter.ALL -> notes
                    SearchTimeFilter.TODAY -> notes.filter { it.updatedAt >= todayMidnight }
                    SearchTimeFilter.THIS_WEEK -> notes.filter { it.updatedAt >= weekStart }
                    SearchTimeFilter.HAS_IMAGE -> notes.filter { it.imageUris.isNotBlank() }
                }
            }
        }

        if (isSearchActive) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                SearchChipsBar(
                    selectedFilter = searchTimeFilter,
                    onSelectFilter = { searchTimeFilter = it }
                )

                if (searchQuery.isBlank()) {
                    SearchRecentView(
                        history = searchHistory,
                        onSelectQuery = { q -> onPerformSearch(q) },
                        onRemoveQuery = { q ->
                            SearchHistoryManager.removeQuery(context, q)
                            searchHistory = SearchHistoryManager.getHistory(context)
                        },
                        onClearAll = {
                            SearchHistoryManager.clearAll(context)
                            searchHistory = emptyList()
                        }
                    )
                } else if (filteredNotes.isEmpty()) {
                    EmptyStateFor(
                        filter = filter,
                        searchQuery = searchQuery,
                        isTrash = false,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp)
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${filteredNotes.size} results for \"$searchQuery\"",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    val (pinned, others) = remember(filteredNotes) { filteredNotes.partition { it.isPinned } }
                    if (viewMode == ViewMode.LINEAR) {
                        LinearNoteList(
                            pinned = pinned,
                            others = others,
                            filter = filter,
                            linearListState = linearListState,
                            isSelectionMode = isSelectionMode,
                            isSelected = { selectedIds.contains(it) },
                            onClickNote = { id ->
                                if (isSelectionMode) toggleSelection(id)
                                else onNoteClick(id)
                            },
                            onLongPress = { id -> if (!isSelectionMode) longPressedNoteId = id },
                            onToggleSelected = { id -> toggleSelection(id) },
                            onRestoreNote = { viewModel.restoreFromTrash(it) },
                            onPermanentDeleteNote = { viewModel.deleteNote(it) }
                        )
                    } else {
                        StaggeredNotesGrid(
                            pinned = pinned,
                            others = others,
                            filter = filter,
                            staggeredGridState = staggeredGridState,
                            isSelectionMode = isSelectionMode,
                            isSelected = { selectedIds.contains(it) },
                            onClickNote = { id ->
                                if (isSelectionMode) toggleSelection(id)
                                else onNoteClick(id)
                            },
                            onLongPress = { id -> if (!isSelectionMode) longPressedNoteId = id },
                            onToggleSelected = { id -> toggleSelection(id) },
                            onRestoreNote = { viewModel.restoreFromTrash(it) },
                            onPermanentDeleteNote = { viewModel.deleteNote(it) }
                        )
                    }
                }
            }
        } else {
            androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                isRefreshing = syncStatus.syncing,
                onRefresh = { viewModel.syncNow() },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (notes.isEmpty()) {
                        EmptyStateFor(
                            filter = filter,
                            searchQuery = searchQuery,
                            isTrash = filter == NotesFilter.TRASH,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp)
                        )
                    } else {
                        val (pinned, others) = remember(notes) { notes.partition { it.isPinned } }
                        if (viewMode == ViewMode.LINEAR) {
                            LinearNoteList(
                                pinned = pinned,
                                others = others,
                                filter = filter,
                                linearListState = linearListState,
                                isSelectionMode = isSelectionMode,
                                isSelected = { selectedIds.contains(it) },
                                onClickNote = { id ->
                                    if (isSelectionMode) toggleSelection(id)
                                    else if (filter != NotesFilter.TRASH) onNoteClick(id)
                                },
                                onLongPress = { id ->
                                    if (!isSelectionMode && filter != NotesFilter.TRASH) {
                                        isSelectionMode = true
                                        selectedIds = setOf(id)
                                    } else if (filter == NotesFilter.TRASH) {
                                        longPressedNoteId = id
                                    }
                                },
                                onToggleSelected = { id -> toggleSelection(id) },
                                onRestoreNote = { viewModel.restoreFromTrash(it) },
                                onPermanentDeleteNote = { viewModel.deleteNote(it) }
                            )
                        } else {
                            StaggeredNotesGrid(
                                pinned = pinned,
                                others = others,
                                filter = filter,
                                staggeredGridState = staggeredGridState,
                                isSelectionMode = isSelectionMode,
                                isSelected = { selectedIds.contains(it) },
                                onClickNote = { id ->
                                    if (isSelectionMode) toggleSelection(id)
                                    else if (filter != NotesFilter.TRASH) onNoteClick(id)
                                },
                                onLongPress = { id ->
                                    if (!isSelectionMode && filter != NotesFilter.TRASH) {
                                        isSelectionMode = true
                                        selectedIds = setOf(id)
                                    } else if (filter == NotesFilter.TRASH) {
                                        longPressedNoteId = id
                                    }
                                },
                                onToggleSelected = { id -> toggleSelection(id) },
                                onRestoreNote = { viewModel.restoreFromTrash(it) },
                                onPermanentDeleteNote = { viewModel.deleteNote(it) }
                            )
                        }
                    }

                    androidx.compose.animation.AnimatedVisibility(
                        visible = showOfflineBanner,
                        enter = androidx.compose.animation.slideInVertically(initialOffsetY = { it }) + androidx.compose.animation.fadeIn(),
                        exit = androidx.compose.animation.slideOutVertically(targetOffsetY = { it }) + androidx.compose.animation.fadeOut(),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 24.dp)
                    ) {
                        androidx.compose.material3.Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.92f),
                            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                            tonalElevation = 6.dp,
                            shadowElevation = 8.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CloudOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.inverseOnSurface
                                )
                                Text(
                                    text = "Offline",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }
            }
        }

    }

    longPressedNoteId?.let { id ->
        val note = notes.find { it.id == id }
        if (note != null) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { longPressedNoteId = null },
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ) {
                NoteActionsSheet(
                    note = note,
                    onTogglePin = {
                        viewModel.togglePin(note)
                        longPressedNoteId = null
                    },
                    onToggleFavorite = {
                        viewModel.toggleFavorite(note)
                        longPressedNoteId = null
                    },
                    onDelete = {
                        if (filter == NotesFilter.TRASH) {
                            viewModel.deleteNote(note)
                        } else {
                            viewModel.moveToTrash(note)
                        }
                        longPressedNoteId = null
                    },
                    isTrashContext = filter == NotesFilter.TRASH
                )
            }
        } else {
            longPressedNoteId = null
        }
    }

    if (showSortViewSheet) {
        SortViewBottomSheet(
            viewMode = viewMode,
            onViewModeChange = { viewMode = it },
            sort = sort,
            onSortChange = { viewModel.setSort(it) },
            onDismiss = { showSortViewSheet = false }
        )
    }

    if (showAccountDialog) {
        AlertDialog(
            onDismissRequest = { showAccountDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 4.dp,
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Google Account",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { showAccountDialog = false },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = avatarLetter,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = if (activeEmail.isNotBlank()) activeEmail else "No account signed in",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Google Keep Sync Faol",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(20.dp))
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            showAccountDialog = false
                            onLogout()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                        )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Chiqish (Log out)",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            },
            confirmButton = {}
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortViewBottomSheet(
    viewMode: ViewMode,
    onViewModeChange: (ViewMode) -> Unit,
    sort: NotesSort,
    onSortChange: (NotesSort) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .background(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(2.dp)
                    )
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Sort & view",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Choose how your notes are arranged",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(20.dp))

            // VIEW SECTION
            Text(
                text = "View",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            SortViewOptionRow(
                icon = Icons.Filled.ViewAgenda,
                label = "List view",
                subtitle = null,
                selected = viewMode == ViewMode.LINEAR,
                onClick = {
                    onViewModeChange(ViewMode.LINEAR)
                    onDismiss()
                }
            )

            SortViewOptionRow(
                icon = Icons.Filled.GridView,
                label = "Grid view",
                subtitle = null,
                selected = viewMode == ViewMode.GRID,
                onClick = {
                    onViewModeChange(ViewMode.GRID)
                    onDismiss()
                }
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            // SORT BY SECTION
            Text(
                text = "Sort by",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            SortViewOptionRow(
                icon = Icons.AutoMirrored.Filled.Sort,
                label = "Updated",
                subtitle = "Newest changes first",
                selected = sort == NotesSort.UPDATED_DESC,
                onClick = {
                    onSortChange(NotesSort.UPDATED_DESC)
                    onDismiss()
                }
            )

            SortViewOptionRow(
                icon = Icons.Filled.Edit,
                label = "Created",
                subtitle = "Newest notes first",
                selected = sort == NotesSort.CREATED_DESC,
                onClick = {
                    onSortChange(NotesSort.CREATED_DESC)
                    onDismiss()
                }
            )

            SortViewOptionRow(
                icon = Icons.AutoMirrored.Filled.Sort,
                label = "Title",
                subtitle = "Alphabetically A–Z",
                selected = sort == NotesSort.TITLE_ASC,
                onClick = {
                    onSortChange(NotesSort.TITLE_ASC)
                    onDismiss()
                }
            )
        }
    }
}

@Composable
private fun SortViewOptionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
                Column {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            androidx.compose.material3.RadioButton(
                selected = selected,
                onClick = onClick,
                colors = androidx.compose.material3.RadioButtonDefaults.colors(
                    selectedColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

/* =====================================================================
 *  List / Grid renderers
 * ===================================================================== */

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LinearNoteList(
    pinned: List<NoteEntity>,
    others: List<NoteEntity>,
    filter: NotesFilter,
    linearListState: androidx.compose.foundation.lazy.LazyListState,
    isSelectionMode: Boolean,
    isSelected: (Long) -> Boolean,
    onClickNote: (Long) -> Unit,
    onLongPress: (Long) -> Unit,
    onToggleSelected: (Long) -> Unit,
    onRestoreNote: (NoteEntity) -> Unit,
    onPermanentDeleteNote: (NoteEntity) -> Unit
) {
    val isTrash = filter == NotesFilter.TRASH
    LazyColumn(
        state = linearListState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (isTrash) {
            item(key = "trash_banner") {
                TrashInfoBanner()
            }
        }
        if (pinned.isNotEmpty()) {
            item(key = "section:pinned") {
                SectionHeader("Pinned", pinned.size)
            }
            items(items = pinned, key = { it.id }) { note ->
                NoteCard(
                    note = note,
                    isSelectionMode = isSelectionMode,
                    isSelected = isSelected(note.id),
                    isTrash = isTrash,
                    onClick = { onClickNote(note.id) },
                    onLongClick = { onLongPress(note.id) },
                    onToggleSelected = { onToggleSelected(note.id) },
                    onRestore = { onRestoreNote(note) },
                    onPermanentDelete = { onPermanentDeleteNote(note) }
                )
            }
        }
        if (others.isNotEmpty()) {
            if (pinned.isNotEmpty()) {
                item(key = "section:others") {
                    SectionHeader("Others", others.size)
                }
            }
            items(items = others, key = { it.id }) { note ->
                NoteCard(
                    note = note,
                    isSelectionMode = isSelectionMode,
                    isSelected = isSelected(note.id),
                    isTrash = isTrash,
                    onClick = { onClickNote(note.id) },
                    onLongClick = { onLongPress(note.id) },
                    onToggleSelected = { onToggleSelected(note.id) },
                    onRestore = { onRestoreNote(note) },
                    onPermanentDelete = { onPermanentDeleteNote(note) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StaggeredNotesGrid(
    pinned: List<NoteEntity>,
    others: List<NoteEntity>,
    filter: NotesFilter,
    staggeredGridState: androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState,
    isSelectionMode: Boolean,
    isSelected: (Long) -> Boolean,
    onClickNote: (Long) -> Unit,
    onLongPress: (Long) -> Unit,
    onToggleSelected: (Long) -> Unit,
    onRestoreNote: (NoteEntity) -> Unit,
    onPermanentDeleteNote: (NoteEntity) -> Unit
) {
    val isTrash = filter == NotesFilter.TRASH
    LazyVerticalStaggeredGrid(
        state = staggeredGridState,
        columns = StaggeredGridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp
        ),
        verticalItemSpacing = 8.dp,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (isTrash) {
            item(key = "trash_banner", span = StaggeredGridItemSpan.FullLine) {
                TrashInfoBanner()
            }
        }
        if (pinned.isNotEmpty()) {
            item(key = "section:pinned", span = StaggeredGridItemSpan.FullLine) {
                SectionHeader("Pinned", pinned.size)
            }
            items(items = pinned, key = { it.id }) { note ->
                NoteStaggeredCard(
                    note = note,
                    isSelectionMode = isSelectionMode,
                    isSelected = isSelected(note.id),
                    isTrash = isTrash,
                    onClick = { onClickNote(note.id) },
                    onLongClick = { onLongPress(note.id) },
                    onToggleSelected = { onToggleSelected(note.id) },
                    onRestore = { onRestoreNote(note) },
                    onPermanentDelete = { onPermanentDeleteNote(note) }
                )
            }
        }
        if (others.isNotEmpty()) {
            if (pinned.isNotEmpty()) {
                item(key = "section:others", span = StaggeredGridItemSpan.FullLine) {
                    SectionHeader("Others", others.size)
                }
            }
            items(items = others, key = { it.id }) { note ->
                NoteStaggeredCard(
                    note = note,
                    isSelectionMode = isSelectionMode,
                    isSelected = isSelected(note.id),
                    isTrash = isTrash,
                    onClick = { onClickNote(note.id) },
                    onLongClick = { onLongPress(note.id) },
                    onToggleSelected = { onToggleSelected(note.id) },
                    onRestore = { onRestoreNote(note) },
                    onPermanentDelete = { onPermanentDeleteNote(note) }
                )
            }
        }
    }
}

/* =====================================================================
 *  Helpers
 * ===================================================================== */

@Composable
private fun SyncStatusText(status: SyncStatus) {
    val text = when {
        status.syncing -> "Syncing..."
        status.error != null -> status.error
        status.lastSyncAgoMs == null -> null
        else -> {
            val ago = System.currentTimeMillis() - status.lastSyncAgoMs
            when {
                ago < 60_000L -> "Last sync: just now"
                ago < 60L * 60_000L -> "Last sync: ${ago / 60_000L}m ago"
                ago < 24L * 60L * 60_000L -> "Last sync: ${ago / (60L * 60_000L)}h ago"
                else -> "Last sync: a long time ago"
            }
        }
    } ?: return

    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Normal
    )
}

@Composable
private fun SectionHeader(label: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/* ----------------------------------------------------------------------- *
 *  Empty states — drawn with `Canvas` in palette colors                   *
 * ----------------------------------------------------------------------- */

@Composable
private fun EmptyStateFor(
    filter: NotesFilter,
    searchQuery: String,
    isTrash: Boolean,
    modifier: Modifier = Modifier
) {
    val hasQuery = searchQuery.isNotBlank()
    val (heading, body, kind) = when {
        hasQuery -> Triple(
            "No matches",
            "Try a different word, or clear the search.",
            EmptyIllustrationKind.SEARCH
        )
        filter == NotesFilter.FAVORITES -> Triple(
            "No favorites yet",
            "Long-press a note and tap the star to add it here.",
            EmptyIllustrationKind.FAVORITE
        )
        isTrash -> Triple(
            "Trash is empty",
            "Deleted notes show up here so you can restore them.",
            EmptyIllustrationKind.TRASH
        )
        else -> Triple(
            "Capture your first note",
            "Tap the Compose button below to start writing.",
            EmptyIllustrationKind.NOTE
        )
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            EmptyIllustration(kind)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = heading,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

private enum class EmptyIllustrationKind { NOTE, SEARCH, FAVORITE, TRASH }

@Composable
private fun EmptyIllustration(kind: EmptyIllustrationKind) {
    val outline = MaterialTheme.colorScheme.onPrimaryContainer
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier = Modifier.size(120.dp)) {
        when (kind) {
            EmptyIllustrationKind.NOTE -> drawNoteIllustration(accent, outline)
            EmptyIllustrationKind.SEARCH -> drawSearchIllustration(accent, outline)
            EmptyIllustrationKind.FAVORITE -> drawFavoriteIllustration(accent, outline)
            EmptyIllustrationKind.TRASH -> drawTrashIllustration(accent, outline)
        }
    }
}

private fun DrawScope.drawNoteIllustration(accent: Color, outline: Color) {
    val stroke = 4.dp.toPx()
    val w = size.width
    val h = size.height

    drawRoundedCard(
        fill = accent.copy(alpha = 0.30f),
        outlineColor = outline.copy(alpha = 0.55f),
        topLeft = Offset(w * 0.32f, h * 0.18f),
        cardSize = Size(w * 0.62f, h * 0.78f),
        cornerRadius = 14.dp.toPx(),
        strokeWidth = stroke
    )
    drawRoundedCard(
        fill = Color.White,
        outlineColor = outline,
        topLeft = Offset(w * 0.18f, h * 0.30f),
        cardSize = Size(w * 0.62f, h * 0.62f),
        cornerRadius = 16.dp.toPx(),
        strokeWidth = stroke
    )

    val cx = w * 0.18f
    val cy = h * 0.30f
    val cw = w * 0.62f
    val lineWidths = listOf(0.75f, 0.55f, 0.30f)
    lineWidths.forEachIndexed { i, f ->
        val y = cy + (h * 0.62f) * (0.30f + i * 0.16f)
        drawLine(
            color = outline.copy(alpha = 0.65f),
            start = Offset(cx + cw * 0.12f, y),
            end = Offset(cx + cw * (0.12f + f), y),
            strokeWidth = 3.dp.toPx()
        )
    }
}

private fun DrawScope.drawSearchIllustration(accent: Color, outline: Color) {
    val w = size.width
    val h = size.height
    val r = w * 0.30f
    val cx = w * 0.46f
    val cy = h * 0.46f

    drawCircle(color = Color.White, radius = r, center = Offset(cx, cy))
    drawCircle(
        color = outline, radius = r, center = Offset(cx, cy),
        style = Stroke(width = 4.dp.toPx())
    )
    drawLine(
        color = outline,
        start = Offset(cx + r * 0.7f, cy + r * 0.7f),
        end = Offset(cx + r * 1.45f, cy + r * 1.45f),
        strokeWidth = 5.dp.toPx()
    )

    val m = 4.dp.toPx()
    drawLine(
        color = accent,
        start = Offset(cx - m * 4, cy - m * 4),
        end = Offset(cx + m * 4, cy + m * 4),
        strokeWidth = 4.dp.toPx()
    )
    drawLine(
        color = accent,
        start = Offset(cx + m * 4, cy - m * 4),
        end = Offset(cx - m * 4, cy + m * 4),
        strokeWidth = 4.dp.toPx()
    )
}

private fun DrawScope.drawFavoriteIllustration(accent: Color, outline: Color) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h / 2f
    val outerR = w * 0.36f
    val innerR = w * 0.16f
    val pts = 5

    val path = Path()
    for (i in 0 until pts * 2) {
        val r = if (i % 2 == 0) outerR else innerR
        val angle = Math.PI / 2 + i * Math.PI / pts
        val px = cx + r * kotlin.math.cos(angle).toFloat()
        val py = cy - r * kotlin.math.sin(angle).toFloat()
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()

    drawPath(path, Color.White)
    drawPath(path, outline, style = Stroke(width = 4.dp.toPx()))
    drawCircle(
        color = accent.copy(alpha = 0.65f),
        radius = innerR * 0.45f,
        center = Offset(cx, cy)
    )
}

private fun DrawScope.drawTrashIllustration(accent: Color, outline: Color) {
    val w = size.width
    val h = size.height
    val lidY = h * 0.32f
    val bodyTop = h * 0.42f
    val bodyBottom = h * 0.86f
    val lidW = w * 0.62f
    val lidX = (w - lidW) / 2f
    val bodyW = w * 0.50f
    val bodyX = (w - bodyW) / 2f

    drawRoundRect(
        color = outline,
        topLeft = Offset(lidX, lidY - 6.dp.toPx()),
        size = Size(lidW, 8.dp.toPx()),
        cornerRadius = CornerRadius(4.dp.toPx())
    )
    drawRoundRect(
        color = outline,
        topLeft = Offset(w / 2f - w * 0.10f, lidY - 18.dp.toPx()),
        size = Size(w * 0.20f, 8.dp.toPx()),
        cornerRadius = CornerRadius(4.dp.toPx())
    )

    drawLine(
        color = outline,
        start = Offset(bodyX, bodyTop),
        end = Offset(bodyX + bodyW, bodyTop),
        strokeWidth = 4.dp.toPx()
    )
    drawLine(
        color = outline,
        start = Offset(bodyX, bodyTop),
        end = Offset(bodyX + bodyW * 0.06f, bodyBottom),
        strokeWidth = 4.dp.toPx()
    )
    drawLine(
        color = outline,
        start = Offset(bodyX + bodyW, bodyTop),
        end = Offset(bodyX + bodyW * 0.94f, bodyBottom),
        strokeWidth = 4.dp.toPx()
    )
    drawLine(
        color = outline,
        start = Offset(bodyX + bodyW * 0.06f, bodyBottom),
        end = Offset(bodyX + bodyW * 0.94f, bodyBottom),
        strokeWidth = 4.dp.toPx()
    )

    val creases = listOf(0.32f, 0.50f, 0.68f)
    creases.forEach { f ->
        val x = bodyX + bodyW * f
        drawLine(
            color = outline.copy(alpha = 0.55f),
            start = Offset(x, bodyTop + 6.dp.toPx()),
            end = Offset(x, bodyBottom - 6.dp.toPx()),
            strokeWidth = 3.dp.toPx()
        )
    }

    drawCircle(
        color = accent.copy(alpha = 0.50f),
        radius = 14.dp.toPx(),
        center = Offset(w / 2f, h * 0.55f)
    )
}

private fun DrawScope.drawRoundedCard(
    fill: Color,
    outlineColor: Color,
    topLeft: Offset,
    cardSize: Size,
    cornerRadius: Float,
    strokeWidth: Float
) {
    drawRoundRect(
        color = fill,
        topLeft = topLeft,
        size = cardSize,
        cornerRadius = CornerRadius(cornerRadius, cornerRadius)
    )
    drawRoundRect(
        color = outlineColor,
        topLeft = topLeft,
        size = cardSize,
        cornerRadius = CornerRadius(cornerRadius, cornerRadius),
        style = Stroke(width = strokeWidth)
    )
}

/* =====================================================================
 *  Pill search bar
 * ===================================================================== */

@Composable
private fun SearchPillField(
    query: String,
    onQueryChange: (String) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = {
                    Text(
                        "Search notes...",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                ),
                textStyle = TextStyle(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal
                ),
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Clear search"
                            )
                        }
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
            )
        }
    }
}

@Composable
private fun SearchChipsBar(
    selectedFilter: SearchTimeFilter,
    onSelectFilter: (SearchTimeFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SearchTimeFilter.values().forEach { filter ->
            val isSelected = filter == selectedFilter
            Surface(
                onClick = { onSelectFilter(filter) },
                shape = CircleShape,
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                border = if (!isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null
            ) {
                Text(
                    text = filter.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp)
                )
            }
        }
    }
}

@Composable
private fun SearchRecentView(
    history: List<String>,
    onSelectQuery: (String) -> Unit,
    onRemoveQuery: (String) -> Unit,
    onClearAll: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        if (history.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent searches",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onClearAll) {
                    Text(
                        text = "Clear all",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            history.forEach { query ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectQuery(query) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = query,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { onRemoveQuery(query) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Remove search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }

        Text(
            text = "Search tips",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Find anything, fast",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Search looks inside note titles, text, checklists and tags. Use \"tag:work\" or \"is:pinned\" to narrow results.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/* =====================================================================
 *  Note cards — list & staggered grid
 * ===================================================================== */

@Composable
private fun NoteCard(
    note: NoteEntity,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    isTrash: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSelected: () -> Unit,
    onRestore: () -> Unit = {},
    onPermanentDelete: () -> Unit = {}
) {
    NoteCardContainer(
        note = note,
        isSelectionMode = isSelectionMode,
        isSelected = isSelected,
        onClick = onClick,
        onLongClick = onLongClick
    ) { onColor, firstImageUri, plainExcerpt ->
        Column(modifier = Modifier.padding(14.dp)) {
            NoteCardHeader(
                title = note.title,
                isPinned = note.isPinned,
                isSelectionMode = isSelectionMode,
                isSelected = isSelected,
                onColor = onColor,
                titleStyle = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                ),
                maxLines = 1,
                pinIconSize = 16.dp,
                onToggleSelected = onToggleSelected
            )

            Spacer(Modifier.height(8.dp))

            if (firstImageUri != null) {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ThumbnailImage(
                        uriString = firstImageUri,
                        modifier = Modifier
                            .size(width = 52.dp, height = 58.dp)
                    )
                    Text(
                        text = plainExcerpt.ifBlank { "No content" },
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
                        color = onColor.copy(alpha = 0.72f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                Text(
                    text = plainExcerpt.ifBlank { "No content" },
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
                    color = onColor.copy(alpha = 0.72f),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(8.dp))

            NoteCardFooter(
                updatedAt = note.updatedAt,
                isFavorite = note.isFavorite,
                isSelectionMode = isSelectionMode,
                isTrash = isTrash,
                onRestore = onRestore,
                onPermanentDelete = onPermanentDelete,
                onColor = onColor,
                starSize = 14.dp
            )
        }
    }
}

@Composable
private fun NoteStaggeredCard(
    note: NoteEntity,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    isTrash: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSelected: () -> Unit,
    onRestore: () -> Unit = {},
    onPermanentDelete: () -> Unit = {}
) {
    NoteCardContainer(
        note = note,
        isSelectionMode = isSelectionMode,
        isSelected = isSelected,
        onClick = onClick,
        onLongClick = onLongClick
    ) { onColor, firstImageUri, plainExcerpt ->
        Column(modifier = Modifier.padding(12.dp)) {
            NoteCardHeader(
                title = note.title,
                isPinned = note.isPinned,
                isSelectionMode = isSelectionMode,
                isSelected = isSelected,
                onColor = onColor,
                titleStyle = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                ),
                maxLines = 2,
                pinIconSize = 14.dp,
                onToggleSelected = onToggleSelected
            )

            if (firstImageUri != null) {
                Spacer(Modifier.height(8.dp))
                ThumbnailImage(
                    uriString = firstImageUri,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(104.dp)
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = plainExcerpt.ifBlank { "No content" },
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = onColor.copy(alpha = 0.72f),
                maxLines = 5,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(8.dp))

            NoteCardFooter(
                updatedAt = note.updatedAt,
                isFavorite = note.isFavorite,
                isSelectionMode = isSelectionMode,
                isTrash = isTrash,
                onRestore = onRestore,
                onPermanentDelete = onPermanentDelete,
                onColor = onColor,
                starSize = 13.dp
            )
        }
    }
}

@Composable
private fun NoteCardContainer(
    note: NoteEntity,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    content: @Composable (onColor: Color, firstImageUri: String?, plainExcerpt: String) -> Unit
) {
    val cardColor = noteCardColor(note.backgroundColor)
    val onColor = noteCardOnColor(note.backgroundColor)
    val firstImageUri = remember(note.id, note.imageUris, note.content) {
        note.imageUris
            .substringBefore("|||")
            .takeIf { it.isNotBlank() && it != note.imageUris }
            ?: note.imageUris.takeIf { it.isNotBlank() && !it.contains("|||") }
            ?: extractFirstImageUriFromContent(note.content)
    }
    val plainExcerpt = remember(note.content) {
        val blocks = parseBlocksFromContent(note.content)
        blocksToPlainText(blocks)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor, contentColor = onColor),
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected)
                MaterialTheme.colorScheme.primary
            else if (note.backgroundColor == 0)
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
            else
                Color.Transparent
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        content(onColor, firstImageUri, plainExcerpt)
    }
}

@Composable
private fun NoteCardHeader(
    title: String,
    isPinned: Boolean,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onColor: Color,
    titleStyle: TextStyle,
    maxLines: Int,
    pinIconSize: androidx.compose.ui.unit.Dp,
    onToggleSelected: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = title.ifBlank { "Untitled" },
            style = titleStyle,
            fontWeight = FontWeight.SemiBold,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            color = onColor,
            modifier = Modifier.weight(1f)
        )
        if (isSelectionMode) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else Color.Transparent
                    )
                    .then(
                        if (!isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        else Modifier
                    )
                    .clickable { onToggleSelected() },
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        } else if (isPinned) {
            Icon(
                imageVector = Icons.Filled.PushPin,
                contentDescription = "Pinned",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(pinIconSize)
            )
        }
    }
}

@Composable
private fun NoteCardFooter(
    updatedAt: Long,
    isFavorite: Boolean,
    isSelectionMode: Boolean,
    isTrash: Boolean = false,
    onRestore: () -> Unit = {},
    onPermanentDelete: () -> Unit = {},
    onColor: Color,
    starSize: androidx.compose.ui.unit.Dp
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isTrash) {
            Text(
                text = "Deleted · 30 days left",
                style = MaterialTheme.typography.labelSmall,
                color = onColor.copy(alpha = 0.65f)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(
                    onClick = onRestore,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Restore,
                        contentDescription = "Restore",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onPermanentDelete,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Delete permanently",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        } else {
            Text(
                text = formatDateShort(updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = onColor.copy(alpha = 0.65f)
            )
            if (isFavorite && !isSelectionMode) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = "Favorite",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(starSize)
                )
            }
        }
    }
}

@Composable
private fun TrashInfoBanner() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "Notes in Trash are deleted automatically after 30 days",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun SelectionActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    val alpha = if (enabled) 1f else 0.38f
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = Color.Transparent,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint.copy(alpha = alpha),
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = tint.copy(alpha = alpha)
            )
        }
    }
}

private fun formatDateShort(ts: Long): String {
    val fmt = SimpleDateFormat("MMM d", Locale.getDefault())
    return fmt.format(Date(ts))
}

private fun extractFirstImageUriFromContent(raw: String): String? {
    if (raw.isBlank()) return null
    val tokenIdx = raw.indexOf("\n|||IMG:")
    if (tokenIdx < 0) return null
    val uriStart = tokenIdx + "\n|||IMG:".length
    val endIdx = raw.indexOf("|||\n", uriStart)
    if (endIdx < 0) return null
    return raw.substring(uriStart, endIdx)
}

@Composable
private fun ThumbnailImage(uriString: String, modifier: Modifier = Modifier) {
    val bitmap = com.example.notes.ui.util.rememberLoadedImage(uriString)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

/* =====================================================================
 *  Long-press bottom sheet
 * ===================================================================== */

@Composable
private fun NoteActionsSheet(
    note: NoteEntity,
    onTogglePin: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
    isTrashContext: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 8.dp, bottom = 24.dp)
    ) {
        Text(
            text = note.title.ifBlank { "Untitled" },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(16.dp))

        BottomSheetAction(
            icon = if (note.isPinned) Icons.Outlined.PushPin else Icons.Filled.PushPin,
            label = if (note.isPinned) "Unpin" else "Pin",
            onClick = onTogglePin
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        BottomSheetAction(
            icon = if (note.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
            label = if (note.isFavorite) "Remove from favorites" else "Add to favorites",
            onClick = onToggleFavorite
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        BottomSheetAction(
            icon = Icons.Filled.Delete,
            label = if (isTrashContext) "Delete permanently" else "Move to trash",
            tint = MaterialTheme.colorScheme.error,
            onClick = onDelete
        )
    }
}

@Composable
private fun BottomSheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
        contentColor = tint
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(imageVector = icon, contentDescription = null)
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
