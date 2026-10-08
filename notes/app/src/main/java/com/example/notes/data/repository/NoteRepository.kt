package com.example.notes.data.repository

import android.content.Context
import com.example.notes.data.local.AuthStore
import com.example.notes.data.local.NoteDao
import com.example.notes.data.local.NoteEntity
import com.example.notes.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/**
 * Single source of truth for note persistence. V3.2: every read is
 * scoped to the currently-active user id (0 when anonymous) so multi-
 * account switching shows the right notes.
 *
 * Callers that need live updates when the active user changes (e.g. after
 * switching accounts in Settings) should collect [userIdFlow] and use it
 * as a trigger to re-subscribe to the data flows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteRepository(
    private val noteDao: NoteDao,
    private val context: Context
) {

    /**
     * Live id of the currently-signed-in user. Emits the new id whenever
     * the active session changes (login / logout / switch). 0 means
     * "anonymous / no session".
     */
    private val _userIdFlow = MutableStateFlow(
        AuthStore.getActive(context)?.userId ?: 0
    )
    val userIdFlow = _userIdFlow.asStateFlow()

    /**
     * Repository-scoped IO scope used for one-shot background jobs that
     * are not tied to any UI lifecycle (e.g. the anonymous-notes
     * migration that runs on sign-in).
     */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Re-reads AuthStore and pushes the new id onto [userIdFlow] so every
     * observer of this repository reloads its data with the right scope.
     * Call this from settings after a switch / logout / login.
     *
     * V4.3.1: when the active user transitions from 0 (anonymous) to a
     * real account, also migrate any locally-saved notes that were
     * created while no user was signed in (userId = 0) over to the new
     * account. Without this those rows would be hidden by the per-user
     * list filter and the user would think their saves "disappeared".
     */
    fun refreshActiveUser() {
        val newId = AuthStore.getActive(context)?.userId ?: 0
        val wasId = _userIdFlow.value
        Logger.d("NoteRepo", "refreshActiveUser: was=$wasId now=$newId")
        if (wasId != newId) {
            _userIdFlow.value = newId
            if (wasId == 0 && newId != 0) {
                ioScope.launch {
                    try {
                        val migrated = noteDao.migrateAnonymousNotesToUser(newId)
                        if (migrated > 0) {
                            Logger.d(
                                "NoteRepo",
                                "migrated $migrated anonymous note(s) to userId=$newId"
                            )
                        }
                    } catch (t: Throwable) {
                        Logger.w("NoteRepo", "anonymous-note migration failed", t)
                    }
                }
            }
        }
    }

    /** Convenience: the latest user id without subscribing to the flow. */
    fun activeUserId(): Int = _userIdFlow.value

    // All active notes for the current user — re-evaluates when the active
    // session changes (login / logout / switch).
    val allNotes: Flow<List<NoteEntity>> =
        userIdFlow.flatMapLatest { noteDao.getAllNotes(it) }
    val noteCount: Flow<Int> =
        userIdFlow.flatMapLatest { noteDao.getNoteCount(it) }

    fun searchNotes(query: String): Flow<List<NoteEntity>> =
        userIdFlow.flatMapLatest { noteDao.searchNotes(query, it) }

    // Favorites
    val favoriteNotes: Flow<List<NoteEntity>> =
        userIdFlow.flatMapLatest { noteDao.getFavoriteNotes(it) }
    val favoriteCount: Flow<Int> =
        userIdFlow.flatMapLatest { noteDao.getFavoriteCount(it) }

    fun searchFavoriteNotes(query: String): Flow<List<NoteEntity>> =
        userIdFlow.flatMapLatest { noteDao.searchFavoriteNotes(query, it) }

    // Trash
    val trashedNotes: Flow<List<NoteEntity>> =
        userIdFlow.flatMapLatest { noteDao.getTrashedNotes(it) }
    val trashedCount: Flow<Int> =
        userIdFlow.flatMapLatest { noteDao.getTrashedCount(it) }

    fun searchTrashedNotes(query: String): Flow<List<NoteEntity>> =
        userIdFlow.flatMapLatest { noteDao.searchTrashedNotes(query, it) }

    suspend fun getNoteById(id: Long): NoteEntity? =
        noteDao.getNoteById(id)

    /**
     * Inserts a new note. [userId] defaults to the active session id
     * (0 when anonymous) so call-sites don't need to think about it.
     *
     * V5.0.2 — serverId is left NULL on insert so the first push can
     * assign it. The push-side dedup in [NotesSyncRepository.pushLocalChanges]
     * generates a UUID on the first attempt and stores it back on the
     * local row only after the server confirms. If the push fails (network
     * error mid-response) the row keeps serverId=null, the next push
     * retries with a fresh UUID, and the pull-time dedup in
     * [NotesSyncRepository.syncIfOnline] will backfill the server's
     * authoritative UUID onto that local copy if it matches by content
     * — so we never end up with two rows for the same note.
     */
    suspend fun insertNote(note: NoteEntity, userId: Int = activeUserId()): Long {
        val stamped = note.copy(userId = userId)
        return noteDao.insertNote(stamped)
    }

    suspend fun updateNote(note: NoteEntity) {
        noteDao.updateNote(note)
    }

    suspend fun deleteNote(note: NoteEntity) {
        noteDao.deleteNote(note)
    }

    suspend fun deleteNoteById(id: Long) {
        noteDao.deleteNoteById(id)
    }

    suspend fun toggleFavorite(note: NoteEntity) {
        noteDao.updateNote(note.copy(isFavorite = !note.isFavorite, updatedAt = System.currentTimeMillis()))
    }

    /**
     * V4.5 — flip the pin flag. Pinned notes float to the top of every
     * list query because the DAO sorts by `isPinned DESC` first.
     */
    suspend fun togglePin(note: NoteEntity) {
        noteDao.updateNote(note.copy(isPinned = !note.isPinned, updatedAt = System.currentTimeMillis()))
    }

    suspend fun moveToTrash(note: NoteEntity) {
        noteDao.updateNote(note.copy(isTrashed = true, updatedAt = System.currentTimeMillis()))
    }

    suspend fun restoreFromTrash(note: NoteEntity) {
        noteDao.updateNote(note.copy(isTrashed = false, updatedAt = System.currentTimeMillis()))
    }
}