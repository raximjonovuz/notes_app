package com.example.notes.ui.notes

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.notes.NotesApplication
import com.example.notes.data.local.NoteEntity
import com.example.notes.data.remote.NotesSyncRepository
import com.example.notes.data.repository.NoteRepository
import com.example.notes.ui.util.ImagePrefetcher
import com.example.notes.util.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

private val httpUrlRegex = Regex("""https?://[^\s"\'|<>]+""")

enum class NotesFilter {
    ALL, FAVORITES, TRASH
}

/**
 * V4.5 — how the list is ordered. Pinned-first is enforced by the SQL
 * ORDER BY for every mode; this enum only chooses the secondary sort.
 */
enum class NotesSort {
    UPDATED_DESC, CREATED_DESC, TITLE_ASC
}

private fun sortNotes(list: List<NoteEntity>, sort: NotesSort): List<NoteEntity> =
    when (sort) {
        NotesSort.UPDATED_DESC -> list
        NotesSort.CREATED_DESC -> list.sortedByDescending { it.createdAt }
        NotesSort.TITLE_ASC -> list.sortedBy { it.title.lowercase() }
    }

data class SyncStatus(
    val syncing: Boolean = false,
    val lastSyncAgoMs: Long? = null,
    val error: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class NotesListViewModel(
    private val repository: NoteRepository,
    private val syncRepository: NotesSyncRepository,
    private val appContext: Context? = null
) : ViewModel() {



    private val _filter = MutableStateFlow(NotesFilter.ALL)
    val filter: StateFlow<NotesFilter> = _filter.asStateFlow()

    private val _sort = MutableStateFlow(NotesSort.UPDATED_DESC)
    val sort: StateFlow<NotesSort> = _sort.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _notes = MutableStateFlow<List<NoteEntity>>(emptyList())
    val notes: StateFlow<List<NoteEntity>> = _notes.asStateFlow()

    private val _noteCount = MutableStateFlow(0)
    val noteCount: StateFlow<Int> = _noteCount.asStateFlow()

    private val _syncStatus = MutableStateFlow(SyncStatus())
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    init {
        // V4.4: debug log goes through Logger so release builds stay quiet.
        Logger.d(
            "NotesListVM",
            "init: activeUserId=${repository.activeUserId()}, allNotes flow=${repository.allNotes}"
        )
        // Observe notes (filter + search + sort)
        viewModelScope.launch {
            _filter.flatMapLatest { currentFilter ->
                _searchQuery.flatMapLatest { query ->
                    _sort.flatMapLatest { currentSort ->
                        val base = when (currentFilter) {
                            NotesFilter.ALL -> {
                                if (query.isBlank()) repository.allNotes
                                else repository.searchNotes(query)
                            }
                            NotesFilter.FAVORITES -> {
                                if (query.isBlank()) repository.favoriteNotes
                                else repository.searchFavoriteNotes(query)
                            }
                            NotesFilter.TRASH -> {
                                if (query.isBlank()) repository.trashedNotes
                                else repository.searchTrashedNotes(query)
                            }
                        }
                        // V5.0.2 — apply the secondary sort in-memory. The
                        // DAO already pins first and orders by updatedAt
                        // DESC, so we only re-sort for createdAt / title.
                        kotlinx.coroutines.flow.flow {
                            base.collect { list ->
                                emit(sortNotes(list, currentSort))
                            }
                        }
                    }
                }
            }.collect { list ->
                _notes.value = list
                if (appContext != null && list.isNotEmpty()) {
                    val allUris = list.flatMap { note ->
                        val fromField = if (note.imageUris.isNotBlank()) note.imageUris.split("|||") else emptyList()
                        val fromBody = httpUrlRegex.findAll(note.content).map { it.value }.toList()
                        fromField + fromBody
                    }
                    ImagePrefetcher.prefetch(appContext, allUris)
                }
            }
        }

        // Observe active (non-trashed) note count
        viewModelScope.launch {
            repository.noteCount.collect { count ->
                _noteCount.value = count
            }
        }

        // Auto-sync notes on screen load
        syncNow()
    }


    fun setFilter(filter: NotesFilter) {
        _filter.value = filter
    }

    fun setSort(sort: NotesSort) {
        _sort.value = sort
    }

    /** V4.3.1: re-emit userIdFlow so the inner flatMapLatest picks up the current account. */
    fun refreshActiveUser() {
        repository.refreshActiveUser()
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

/**
     * V5.3 — soft-delete with server confirmation. The local row is
     * marked with `deletedAt` immediately (so it disappears from the
     * list view and is excluded from the next push's "live" pass). The
     * server-side DELETE happens via the next push round-trip; once
     * the pull side sees the server's tombstone in `change_log`, the
     * row is purged from the local DB.
     *
     * BUG 2 fix: there is no longer a path where the local row is
     * gone but the server still has it. If the user is offline, the
     * row is only hidden locally, the server row is preserved, and a
     * later push will issue the DELETE for us.
     */
    fun deleteNote(note: NoteEntity) {
        viewModelScope.launch {
            repository.deleteNote(note)
            pushSilently()
        }
    }

    fun toggleFavorite(note: NoteEntity) {
        viewModelScope.launch {
            repository.toggleFavorite(note)
            // V4.4: favourite is a synced field, propagate.
            pushSilentlyDebounced()
        }
    }

    fun togglePin(note: NoteEntity) {
        viewModelScope.launch {
            repository.togglePin(note)
            pushSilentlyDebounced()
        }
    }

    fun moveToTrash(note: NoteEntity) {
        viewModelScope.launch {
            repository.moveToTrash(note)
            // V4.4: trash is a synced field — other devices should see
            // the same note in their trash. Force-push immediately so
            // other devices don't have to wait for the debounce.
            viewModelScope.launch {
                pushSilently()
            }
        }
    }

    fun restoreFromTrash(note: NoteEntity) {
        viewModelScope.launch {
            repository.restoreFromTrash(note)
            pushSilentlyDebounced()
        }
    }

    /**
     * V5.0.2 — fire-and-forget push coalescing. The auto-save and toggle
     * flows call this so a rapid series of edits collapses into a single
     * network round-trip after the debounce window.
     */
    private fun pushSilentlyDebounced() {
        syncRepository.pushLocalChangesDebounced()
    }

    /**
     * V4.4 — best-effort push of any local mutations to the server.
     * Wrapped in try/catch so a network failure never breaks a local
     * edit; the next manual Sync will retry.
     */
    private suspend fun pushSilently() {
        try {
            val pushed = syncRepository.flushPendingPush()
            if (pushed > 0) {
                Logger.d("NotesListVM", "pushSilently: pushed $pushed note(s)")
            }
        } catch (t: Throwable) {
            Logger.w("NotesListVM", "pushSilently failed (will retry on next sync)", t)
        }
    }

    /**
     * Kick off a sync round-trip. The result (success/failure) is reflected
     * in [syncStatus] for the UI to render. Safe to call from a button tap.
     */
    fun syncNow() {
        if (_syncStatus.value.syncing) return
        _syncStatus.value = _syncStatus.value.copy(syncing = true, error = null)
        viewModelScope.launch {
            // B2: syncIfOnline now runs pull → push internally. We don't
            // need a separate flushPendingPush here — the sync repository
            // drains the queue inside the same mutex. Kept as a no-cost
            // safety net in case the debounce timer is mid-flight: a
            // flushPendingPush just runs the same push pass under the
            // same mutex and returns immediately.
            val outcome = syncRepository.syncIfOnline()
            _syncStatus.value = when (outcome) {
                is NotesSyncRepository.SyncOutcome.Success -> SyncStatus(
                    syncing = false,
                    lastSyncAgoMs = System.currentTimeMillis(),
                    error = null
                )
                is NotesSyncRepository.SyncOutcome.Unauthorized -> SyncStatus(
                    syncing = false,
                    lastSyncAgoMs = _syncStatus.value.lastSyncAgoMs,
                    error = "Session expired. Please sign in again."
                )
                is NotesSyncRepository.SyncOutcome.Failed -> SyncStatus(
                    syncing = false,
                    lastSyncAgoMs = _syncStatus.value.lastSyncAgoMs,
                    error = "Sync failed: ${outcome.reason ?: "network error"}"
                )
                NotesSyncRepository.SyncOutcome.Skipped -> SyncStatus(
                    syncing = false,
                    lastSyncAgoMs = _syncStatus.value.lastSyncAgoMs,
                    error = "Sync skipped (offline or not signed in)"
                )
            }
        }
    }

    /**
     * Factory that builds the view model from an [Application] — we need
     * the application context to construct the [NotesSyncRepository] (for
     * the ConnectivityManager and SharedPreferences).
     *
     * B3 — uses the [NotesApplication.syncRepository] singleton. The
     * previous implementation constructed a fresh
     * [NotesSyncRepository] on every ViewModel create, which meant
     * the BackgroundSyncWorker (which also uses the singleton) and
     * the ViewModel had two independent mutexes and two independent
     * SyncStore caches. That allowed a push from the worker to
     * interleave with a push from the editor on the same cursor and
     * corrupt the per-user namespace introduced in B6.
     */
    class Factory(
        private val repository: NoteRepository,
        private val application: Application
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(NotesListViewModel::class.java)) {
                val app = application as NotesApplication
                return NotesListViewModel(repository, app.syncRepository, app.applicationContext) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}