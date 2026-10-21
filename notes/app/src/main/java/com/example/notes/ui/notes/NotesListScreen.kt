package com.example.notes.ui.notes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff

import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
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
import androidx.compose.material3.ExtendedFloatingActionButton
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
    onMenuClick: () -> Unit
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


    var isSearchActive by remember { mutableStateOf(false) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var viewMode by remember { mutableStateOf(ViewMode.LINEAR) }
    var overflowExpanded by remember { mutableStateOf(false) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var longPressedNoteId by remember { mutableStateOf<Long?>(null) }

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
            TopAppBar(
                title = {
                    when {
                        isSelectionMode -> Text(
                            text = "${selectedIds.size} selected",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        isSearchActive -> SearchPillField(
                            query = searchQuery,
                            onQueryChange = viewModel::onSearchQueryChange,
                            focusRequester = searchFocusRequester,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        )
                        else -> Column {
                            Text(
                                text = titleText,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "$noteCount notes",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            SyncStatusText(status = syncStatus)
                        }
                    }
                },
                navigationIcon = {
                    when {
                        isSelectionMode -> IconButton(onClick = {
                            isSelectionMode = false
                            selectedIds = emptySet()
                        }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Exit selection"
                            )
                        }
                        isSearchActive -> IconButton(onClick = {
                            isSearchActive = false
                            viewModel.onSearchQueryChange("")
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Close search"
                            )
                        }
                        else -> IconButton(onClick = onMenuClick) {
                            Icon(Icons.Filled.Menu, contentDescription = "Menu")
                        }
                    }
                },
                actions = {
                    if (isSelectionMode) {
                        if (notes.isNotEmpty()) {
                            val allSelected = selectedIds.size == notes.size
                            TextButton(onClick = {
                                selectedIds = if (allSelected) emptySet()
                                else notes.map { it.id }.toSet()
                            }) {
                                Text(
                                    text = if (allSelected) "Deselect all" else "Select all",
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                selectedIds.forEach { id ->
                                    val note = notes.find { it.id == id }
                                    if (note != null) {
                                        if (filter == NotesFilter.TRASH)
                                            viewModel.deleteNote(note)
                                        else viewModel.moveToTrash(note)
                                    }
                                }
                                isSelectionMode = false
                                selectedIds = emptySet()
                            },
                            enabled = selectedIds.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "Delete selected",
                                tint = if (selectedIds.isNotEmpty())
                                    MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else if (isSearchActive) {
                        // Search mode: no extra actions.
                    } else {
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = "Search"
                            )
                        }
                        IconButton(onClick = { viewModel.syncNow() }) {
                            Icon(
                                imageVector = Icons.Filled.Sync,
                                contentDescription = "Sync"
                            )
                        }
                        Box {
                            IconButton(onClick = { overflowExpanded = true }) {
                                Icon(
                                    imageVector = Icons.Filled.MoreVert,
                                    contentDescription = "More"
                                )
                            }
                            DropdownMenu(
                                expanded = overflowExpanded,
                                onDismissRequest = { overflowExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Select") },
                                    leadingIcon = { Icon(Icons.Filled.CheckBox, contentDescription = null) },
                                    onClick = {
                                        overflowExpanded = false
                                        isSelectionMode = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (viewMode == ViewMode.LINEAR) "Grid view"
                                            else "List view"
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (viewMode == ViewMode.LINEAR)
                                                Icons.Filled.GridView
                                            else Icons.Filled.ViewAgenda,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        overflowExpanded = false
                                        viewMode = if (viewMode == ViewMode.LINEAR)
                                            ViewMode.GRID
                                        else ViewMode.LINEAR
                                    }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Sort: ${
                                                when (sort) {
                                                    NotesSort.UPDATED_DESC -> "Updated (newest)"
                                                    NotesSort.CREATED_DESC -> "Created (newest)"
                                                    NotesSort.TITLE_ASC -> "Title (A→Z)"
                                                }
                                            }"
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null)
                                    },
                                    onClick = {
                                        overflowExpanded = false
                                        sortMenuExpanded = true
                                    }
                                )
                            }
                            DropdownMenu(
                                expanded = sortMenuExpanded,
                                onDismissRequest = { sortMenuExpanded = false }
                            ) {
                                NotesSort.values().forEach { option ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                when (option) {
                                                    NotesSort.UPDATED_DESC -> "Updated (newest)"
                                                    NotesSort.CREATED_DESC -> "Created (newest)"
                                                    NotesSort.TITLE_ASC -> "Title (A→Z)"
                                                }
                                            )
                                        },
                                        leadingIcon = {
                                            if (sort == option) {
                                                Icon(Icons.Filled.Check, contentDescription = null)
                                            }
                                        },
                                        onClick = {
                                            viewModel.setSort(option)
                                            sortMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = notesHeaderBackground(),
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        floatingActionButton = {
            if (!isSelectionMode && filter != NotesFilter.TRASH) {
                val linearCollapsed by remember {
                    derivedStateOf {
                        linearListState.firstVisibleItemIndex > 0 ||
                            linearListState.firstVisibleItemScrollOffset > 240
                    }
                }
                val gridCollapsed by remember {
                    derivedStateOf {
                        staggeredGridState.firstVisibleItemIndex > 0 ||
                            staggeredGridState.firstVisibleItemScrollOffset > 240
                    }
                }
                val fabCollapsed =
                    if (viewMode == ViewMode.LINEAR) linearCollapsed else gridCollapsed

                ExtendedFloatingActionButton(
                    onClick = onAddNote,
                    expanded = !fabCollapsed,
                    text = { Text("Compose") },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = "Compose note"
                        )
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        },
        floatingActionButtonPosition = androidx.compose.material3.FabPosition.Center,
        containerColor = notesHeaderBackground()
    ) { innerPadding ->
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
                            linearListState = linearListState,
                            isSelectionMode = isSelectionMode,
                            isSelected = { selectedIds.contains(it) },
                            onClickNote = { id ->
                                if (isSelectionMode) toggleSelection(id)
                                else if (filter != NotesFilter.TRASH) onNoteClick(id)
                            },
                            onLongPress = { id -> if (!isSelectionMode) longPressedNoteId = id },
                            onToggleSelected = { id -> toggleSelection(id) }
                        )
                    } else {
                        StaggeredNotesGrid(
                            pinned = pinned,
                            others = others,
                            staggeredGridState = staggeredGridState,
                            isSelectionMode = isSelectionMode,
                            isSelected = { selectedIds.contains(it) },
                            onClickNote = { id ->
                                if (isSelectionMode) toggleSelection(id)
                                else if (filter != NotesFilter.TRASH) onNoteClick(id)
                            },
                            onLongPress = { id -> if (!isSelectionMode) longPressedNoteId = id },
                            onToggleSelected = { id -> toggleSelection(id) }
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
}

/* =====================================================================
 *  List / Grid renderers
 * ===================================================================== */

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LinearNoteList(
    pinned: List<NoteEntity>,
    others: List<NoteEntity>,
    linearListState: androidx.compose.foundation.lazy.LazyListState,
    isSelectionMode: Boolean,
    isSelected: (Long) -> Boolean,
    onClickNote: (Long) -> Unit,
    onLongPress: (Long) -> Unit,
    onToggleSelected: (Long) -> Unit
) {
    LazyColumn(
        state = linearListState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (pinned.isNotEmpty()) {
            item(key = "section:pinned") {
                SectionHeader("Pinned", pinned.size)
            }
            items(items = pinned, key = { it.id }) { note ->
                NoteCard(
                    note = note,
                    isSelectionMode = isSelectionMode,
                    isSelected = isSelected(note.id),
                    onClick = { onClickNote(note.id) },
                    onLongClick = { onLongPress(note.id) },
                    onToggleSelected = { onToggleSelected(note.id) }
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
                    onClick = { onClickNote(note.id) },
                    onLongClick = { onLongPress(note.id) },
                    onToggleSelected = { onToggleSelected(note.id) }
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
    staggeredGridState: androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState,
    isSelectionMode: Boolean,
    isSelected: (Long) -> Boolean,
    onClickNote: (Long) -> Unit,
    onLongPress: (Long) -> Unit,
    onToggleSelected: (Long) -> Unit
) {
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
        if (pinned.isNotEmpty()) {
            item(key = "section:pinned", span = StaggeredGridItemSpan.FullLine) {
                SectionHeader("Pinned", pinned.size)
            }
            items(items = pinned, key = { it.id }) { note ->
                NoteStaggeredCard(
                    note = note,
                    isSelectionMode = isSelectionMode,
                    isSelected = isSelected(note.id),
                    onClick = { onClickNote(note.id) },
                    onLongClick = { onLongPress(note.id) },
                    onToggleSelected = { onToggleSelected(note.id) }
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
                    onClick = { onClickNote(note.id) },
                    onLongClick = { onLongPress(note.id) },
                    onToggleSelected = { onToggleSelected(note.id) }
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
                .size(192.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)),
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

/* =====================================================================
 *  Note cards — list & staggered grid
 * ===================================================================== */

@Composable
private fun NoteCard(
    note: NoteEntity,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSelected: () -> Unit
) {
    NoteCardContainer(
        note = note,
        isSelectionMode = isSelectionMode,
        isSelected = isSelected,
        onClick = onClick,
        onLongClick = onLongClick
    ) { onColor, firstImageUri, plainExcerpt ->
        Column(modifier = Modifier.padding(16.dp)) {
            NoteCardHeader(
                title = note.title,
                isPinned = note.isPinned,
                isSelectionMode = isSelectionMode,
                isSelected = isSelected,
                onColor = onColor,
                titleStyle = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                pinIconSize = 16.dp,
                onToggleSelected = onToggleSelected
            )

            Spacer(Modifier.height(8.dp))

            if (firstImageUri != null) {
                Row(verticalAlignment = Alignment.Top) {
                    ThumbnailImage(
                        uriString = firstImageUri,
                        modifier = Modifier
                            .size(56.dp)
                            .padding(end = 12.dp)
                    )
                    Text(
                        text = plainExcerpt.ifBlank { "No content" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = onColor.copy(alpha = 0.75f),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                Text(
                    text = plainExcerpt.ifBlank { "No content" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = onColor.copy(alpha = 0.75f),
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(8.dp))

            NoteCardFooter(
                updatedAt = note.updatedAt,
                isFavorite = note.isFavorite,
                isSelectionMode = isSelectionMode,
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
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSelected: () -> Unit
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
                titleStyle = MaterialTheme.typography.titleSmall,
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
                        .height(96.dp)
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = plainExcerpt.ifBlank { "No content" },
                style = MaterialTheme.typography.bodySmall,
                color = onColor.copy(alpha = 0.75f),
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(8.dp))

            NoteCardFooter(
                updatedAt = note.updatedAt,
                isFavorite = note.isFavorite,
                isSelectionMode = isSelectionMode,
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
            .clip(MaterialTheme.shapes.large)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = cardColor, contentColor = onColor),
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected)
                MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant
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
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelected() }
            )
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
    onColor: Color,
    starSize: androidx.compose.ui.unit.Dp
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
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
            .clip(RoundedCornerShape(12.dp))
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
