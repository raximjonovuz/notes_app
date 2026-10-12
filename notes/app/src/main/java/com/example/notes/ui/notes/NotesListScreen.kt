package com.example.notes.ui.notes

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.notes.data.local.NoteEntity
import com.example.notes.ui.edit.blocksToPlainText
import com.example.notes.ui.edit.parseBlocksFromContent
import com.example.notes.ui.theme.noteCardColor
import com.example.notes.ui.theme.notesHeaderBackground
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class ViewMode { LINEAR, GRID }

/* -----------------------------------------------------------------------
 * Date grouping helpers
 * --------------------------------------------------------------------- */

/**
 * Build section labels like "Today", "Yesterday", or "MMM dd, yyyy" for the
 * given timestamp using the device's local timezone (the default Calendar
 * / Date uses the device zone).
 */
private fun sectionLabelFor(timestamp: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = timestamp }

    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    if (sameDay) return "Today"

    val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    val isYesterday = yesterday.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        yesterday.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    if (isYesterday) return "Yesterday"

    val formatter = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    return formatter.format(Date(timestamp))
}

/**
 * Group a list of notes into sections. The notes MUST be in updatedAt DESC
 * order (the ViewModel already enforces that). The returned list preserves
 * the same note order while inserting a section-header marker in front of
 * each group.
 */
private fun groupNotesByDate(notes: List<NoteEntity>): List<Any> {
    if (notes.isEmpty()) return emptyList()
    val result = mutableListOf<Any>()
    var currentLabel: String? = null
    notes.forEach { note ->
        val label = sectionLabelFor(note.updatedAt)
        if (label != currentLabel) {
            result += label
            currentLabel = label
        }
        result += note
    }
    return result
}

/* -----------------------------------------------------------------------
 * Screen
 * --------------------------------------------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
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

    // V4.3.1: when the screen is first composed, re-read the active
    // user from AuthStore and re-emit userIdFlow. This guarantees
    // NotesListViewModel.s flatMapLatest picks up the right userId
    // even if the screen is shown right after a Google Sign-In
    // completes.
    LaunchedEffect(Unit) {
        viewModel.refreshActiveUser()
    }

    var isSearchActive by remember { mutableStateOf(false) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var viewMode by remember { mutableStateOf(ViewMode.LINEAR) }
    var overflowExpanded by remember { mutableStateOf(false) }
    var sortMenuExpanded by remember { mutableStateOf(false) }

    val searchFocusRequester = remember { FocusRequester() }

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) searchFocusRequester.requestFocus()
    }

    val (titleText, emptyText) = when (filter) {
        NotesFilter.ALL -> "All Notes" to "No notes yet.\nTap + to create one."
        NotesFilter.FAVORITES -> "Favorites" to "No favorites yet.\nTap the star on a note to add it here."
        NotesFilter.TRASH -> "Trash" to "Trash is empty."
    }

    fun toggleSelection(noteId: Long) {
        selectedIds = if (selectedIds.contains(noteId)) {
            selectedIds - noteId
        } else {
            selectedIds + noteId
        }
        if (selectedIds.isEmpty()) isSelectionMode = false
    }

    val headerBg = notesHeaderBackground()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    when {
                        isSelectionMode -> {
                            Text(
                                text = "${selectedIds.size} selected",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        isSearchActive -> {
                            // Issue 1: search field uses notesHeaderBackground()
                            // for ALL container / indicator colors so it looks
                            // visually unified with the surrounding header bar.
                            TextField(
                                value = searchQuery,
                                onValueChange = viewModel::onSearchQueryChange,
                                placeholder = {
                                    Text(
                                        "Search notes...",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = headerBg,
                                    unfocusedContainerColor = headerBg,
                                    focusedIndicatorColor = headerBg,
                                    unfocusedIndicatorColor = headerBg,
                                    disabledContainerColor = headerBg,
                                    disabledIndicatorColor = headerBg
                                ),
                                textStyle = TextStyle(
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                trailingIcon = {
                                    // V4.5: a one-tap clear (×) so the user
                                    // doesn't have to long-press to select
                                    // the text and delete it.
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = {
                                            viewModel.onSearchQueryChange("")
                                        }) {
                                                Icon(
                                                    imageVector = Icons.Filled.Close,
                                                    contentDescription = "Clear search"
                                                )
                                            }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(searchFocusRequester)
                            )
                        }
                        else -> {
                            Column {
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
                    }
                },
                navigationIcon = {
                    when {
                        isSelectionMode -> {
                            IconButton(onClick = {
                                isSelectionMode = false
                                selectedIds = emptySet()
                            }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Exit selection"
                                )
                            }
                        }
                        isSearchActive -> {
                            IconButton(onClick = {
                                isSearchActive = false
                                viewModel.onSearchQueryChange("")
                            }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Close search"
                                )
                            }
                        }
                        else -> {
                            IconButton(onClick = onMenuClick) {
                                Icon(
                                    imageVector = Icons.Filled.Menu,
                                    contentDescription = "Menu"
                                )
                            }
                        }
                    }
                },
                actions = {
                    if (isSelectionMode) {
                        // V5.0.2: "Select all" so the user can wipe dozens
                        // of accidentally-created test notes with two taps
                        // instead of ticking every checkbox individually.
                        // Only offered when the current list has at least
                        // one note — hiding the action on an empty page
                        // avoids confusing empty-state affordances.
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
                                        if (filter == NotesFilter.TRASH) {
                                            viewModel.deleteNote(note)
                                        } else {
                                            viewModel.moveToTrash(note)
                                        }
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
                                        Text(if (viewMode == ViewMode.LINEAR) "Grid view" else "List view")
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
                                            ViewMode.GRID else ViewMode.LINEAR
                                    }
                                )
                                // V4.5: sort submenu — the picker itself lives
                                // outside this dropdown so we can manage its
                                // open state independently.
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Sort: ${
                                                when (viewModel.sort.value) {
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
                            // Sort sub-dropdown (rendered as a separate
                            // DropdownMenu anchored to the same overflow
                            // trigger; uses its own open state).
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
                                            if (viewModel.sort.value == option) {
                                                Icon(
                                                    Icons.Filled.Check,
                                                    contentDescription = null
                                                )
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
                    containerColor = headerBg,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        floatingActionButton = {
            if (!isSelectionMode && filter != NotesFilter.TRASH) {
                // ExtendedFloatingActionButton (vs V1 floating "+" only)
                // — the explicit "Compose" text stops users from confusing
                // the new-note FAB with the Sync icon in the top bar.
                // Position is bumped to Center to put more visual space
                // between FAB and Sync.
                androidx.compose.material3.ExtendedFloatingActionButton(
                    onClick = onAddNote,
                    text = { Text("Compose") },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = "Compose note"
                        )
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            }
        },
        floatingActionButtonPosition = androidx.compose.material3.FabPosition.Center
    ) { innerPadding ->
        // V5.1.5 — wrap the body in a pull-to-refresh container so the
        // user can trigger sync by pulling the list down. The sync icon
        // in the top bar stays for users who prefer one-tap; both call
        // the same viewModel.syncNow().
        val isRefreshing = syncStatus.syncing
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.syncNow() },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
            if (notes.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    // V5.1.5 — friendlier empty state with a larger icon
                    // circle, the actual situation-specific message, and a
                    // hint that tells the user what to do next.
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(96.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant
                                        .copy(alpha = 0.6f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = when {
                                    searchQuery.isNotBlank() -> Icons.Filled.SearchOff
                                    filter == NotesFilter.FAVORITES -> Icons.Filled.StarBorder
                                    filter == NotesFilter.TRASH -> Icons.Outlined.Delete
                                    else -> Icons.Filled.NoteAlt
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(48.dp)
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = if (searchQuery.isNotBlank()) {
                                "No notes for \"${searchQuery.trim()}\""
                            } else emptyText,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = when {
                                searchQuery.isNotBlank() -> "Try a different search term."
                                filter == NotesFilter.FAVORITES -> "Tap the star on a note to add it here."
                                filter == NotesFilter.TRASH -> "Trash is empty. Deleted notes show up here."
                                else -> "Tap the Compose button below to write your first note."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else if (viewMode == ViewMode.LINEAR) {
                // Issue 2: group notes by their updatedAt date. Section
                // headers sit at the TOP of each group. Within each group
                // notes are already sorted by updatedAt DESC.
                val items = groupNotesByDate(notes)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 16.dp,
                        bottom = 88.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = items,
                        key = { item ->
                            when (item) {
                                is String -> "section:$item"
                                is NoteEntity -> "note:${item.id}"
                                else -> item.hashCode().toString()
                            }
                        }
                    ) { item ->
                        when (item) {
                            is String -> SectionHeader(label = item)
                            is NoteEntity -> NoteCard(
                                note = item,
                                isSelectionMode = isSelectionMode,
                                isSelected = selectedIds.contains(item.id),
                                onClick = {
                                    if (isSelectionMode) toggleSelection(item.id)
                                    else if (filter != NotesFilter.TRASH) onNoteClick(item.id)
                                },
                                onToggleSelected = { toggleSelection(item.id) },
                                onToggleFavorite = { viewModel.toggleFavorite(item) },
                                onTogglePin = { viewModel.togglePin(item) }
                            )
                        }
                    }
                }
            } else {
                // Grid view (no date grouping — kept simple as before).
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(notes, key = { it.id }) { note ->
                        NoteGridCard(
                            note = note,
                            isSelectionMode = isSelectionMode,
                            isSelected = selectedIds.contains(note.id),
                            onClick = {
                                if (isSelectionMode) toggleSelection(note.id)
                                else if (filter != NotesFilter.TRASH) onNoteClick(note.id)
                            },
                            onToggleFavorite = { viewModel.toggleFavorite(note) }
                        )
                    }
                }
            }
            }
        }
    }
}

/* -----------------------------------------------------------------------
 * Section header (grouping)
 * --------------------------------------------------------------------- */

/**
 * Header sub-line that reflects the most recent sync outcome:
 *   - "Syncing..." while a round-trip is in flight
 *   - "Last sync: just now / Xm ago / Xh ago" after success
 *   - "Offline" or "Sync failed" when the last attempt errored
 *   - (no row) when we have never synced AND there is no error
 */
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

/** Sticky-ish section header that sits at the TOP of each date group. */
@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp)
    )
}

/* -----------------------------------------------------------------------
 * Note cards
 * --------------------------------------------------------------------- */

/**
 * Linear note card. Date / time are NO LONGER rendered inside the card —
 * the section header at the top of each group already conveys the date.
 * The card itself shows: title + thumbnail (first inline image if any) +
 * text excerpt. A star toggle is in the top-right corner.
 */
@Composable
private fun NoteCard(
    note: NoteEntity,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onToggleSelected: () -> Unit,
    onToggleFavorite: () -> Unit,
    onTogglePin: () -> Unit
) {
    val cardColor = noteCardColor(note.backgroundColor)
    val firstImageUri = remember(note.id, note.imageUris, note.content) {
        // Prefer the legacy V5 imageUris column; fall back to parsing the
        // V6 content tokens.
        note.imageUris
            .substringBefore("|||")
            .takeIf { it.isNotBlank() && it != note.imageUris }
            ?: note.imageUris.takeIf { it.isNotBlank() && !it.contains("|||") }
            ?: extractFirstImageUriFromContent(note.content)
    }
    val plainExcerpt = remember(note.content) {
        // V2.6 content is wrapped in `<s|a|l|k>S<ranges>…` headers; show
        // the readable plain text in the list, not the serialized form.
        val blocks = parseBlocksFromContent(note.content)
        blocksToPlainText(blocks)
    }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isSelected) Modifier.border(
                    2.dp,
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(12.dp)
                ) else Modifier
            ),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = note.title.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (isSelectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelected() }
                    )
                } else {
                    // V4.5: pin toggle sits to the left of the star so the
                    // pinned-vs-favorite action order reads naturally.
                    IconButton(
                        onClick = onTogglePin,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (note.isPinned)
                                Icons.Filled.PushPin
                            else
                                Icons.Outlined.PushPin,
                            contentDescription = if (note.isPinned) "Unpin" else "Pin",
                            tint = if (note.isPinned)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (note.isFavorite)
                                Icons.Filled.Star
                            else
                                Icons.Filled.StarBorder,
                            contentDescription = if (note.isFavorite) "Unfavorite" else "Favorite",
                            tint = if (note.isFavorite)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // Thumbnail (if there's an image) shown to the left of the text
            // excerpt.
            if (firstImageUri != null) {
                Row(verticalAlignment = Alignment.Top) {
                    ThumbnailImage(
                        uriString = firstImageUri,
                        modifier = Modifier
                            .size(56.dp)
                            .padding(end = 8.dp)
                    )
                    Text(
                        text = plainExcerpt.ifBlank { "No content" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                Text(
                    text = plainExcerpt.ifBlank { "No content" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Pull the first inline image URI out of a serialized content string. */
private fun extractFirstImageUriFromContent(raw: String): String? {
    if (raw.isBlank()) return null
    val tokenIdx = raw.indexOf("\n|||IMG:")
    if (tokenIdx < 0) return null
    val uriStart = tokenIdx + "\n|||IMG:".length
    val endIdx = raw.indexOf("|||\n", uriStart)
    if (endIdx < 0) return null
    return raw.substring(uriStart, endIdx)
}

/** Lightweight thumbnail (no special caching — list cards stay snappy). */
@Composable
private fun ThumbnailImage(uriString: String, modifier: Modifier = Modifier) {
    // V5.2.3 — decode off the UI thread so an https:// thumbnail for a
    // freshly-uploaded note can't freeze the list when the network is
    // slow. The previous implementation did the read inside
    // `remember { … }` on the main thread.
    val bitmap = com.example.notes.ui.util.rememberLoadedImage(uriString)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
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

@Composable
private fun NoteGridCard(
    note: NoteEntity,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val cardColor = noteCardColor(note.backgroundColor)
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .then(
                if (isSelected) Modifier.border(
                    2.dp,
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(12.dp)
                ) else Modifier
            ),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = note.title.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (isSelectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onClick() }
                    )
                } else {
                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = if (note.isFavorite)
                                Icons.Filled.Star
                            else
                                Icons.Filled.StarBorder,
                            contentDescription = if (note.isFavorite) "Unfavorite" else "Favorite",
                            tint = if (note.isFavorite)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            val plainExcerpt = remember(note.content) {
                val blocks = parseBlocksFromContent(note.content)
                blocksToPlainText(blocks)
            }
            Text(
                text = plainExcerpt.ifBlank { "No content" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
