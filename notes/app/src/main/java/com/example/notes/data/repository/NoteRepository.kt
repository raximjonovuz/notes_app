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
 * V5.3 — single source of truth for note persistence.
 *
 * 1. Every read is scoped to the currently-active user id.
 * 2. Every mutation bumps `dirtyRevision` so the push side can drain
 *    "needs sync" rows independently of `serverId`.
 * 3. Deletes are now soft-deletes (`deletedAt = now`) until the server
 *    acknowledges them via a subsequent pull that includes the
 *    tombstone; then the row is purged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteRepository(
    private val noteDao: NoteDao,
    private val context: Context
) {

    private val _userIdFlow = MutableStateFlow(
        AuthStore.getActive(context)?.userId ?: 0
    )
    val userIdFlow = _userIdFlow.asStateFlow()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    fun activeUserId(): Int = _userIdFlow.value

    val allNotes: Flow<List<NoteEntity>> =
        userIdFlow.flatMapLatest { noteDao.getAllNotes(it) }
    val noteCount: Flow<Int> =
        userIdFlow.flatMapLatest { noteDao.getNoteCount(it) }

    fun searchNotes(query: String): Flow<List<NoteEntity>> =
        userIdFlow.flatMapLatest { noteDao.searchNotes(query, it) }

    val favoriteNotes: Flow<List<NoteEntity>> =
        userIdFlow.flatMapLatest { noteDao.getFavoriteNotes(it) }
    val favoriteCount: Flow<Int> =
        userIdFlow.flatMapLatest { noteDao.getFavoriteCount(it) }

    fun searchFavoriteNotes(query: String): Flow<List<NoteEntity>> =
        userIdFlow.flatMapLatest { noteDao.searchFavoriteNotes(query, it) }

    val trashedNotes: Flow<List<NoteEntity>> =
        userIdFlow.flatMapLatest { noteDao.getTrashedNotes(it) }
    val trashedCount: Flow<Int> =
        userIdFlow.flatMapLatest { noteDao.getTrashedCount(it) }

    fun searchTrashedNotes(query: String): Flow<List<NoteEntity>> =
        userIdFlow.flatMapLatest { noteDao.searchTrashedNotes(query, it) }

    suspend fun getNoteById(id: Long): NoteEntity? =
        noteDao.getNoteById(id)

    /**
     * V5.3.4 / B3 — first-insert. `serverId` is now stamped with a
     * locally-generated UUID at insert time, not at push time. The
     * server's UPSERT (B3) accepts our UUID and echoes it back
     * unchanged, so the local row's `serverId` and primary key never
     * need to change after the first push — and the UNIQUE index on
     * `serverId` is now safe.
     *
     * dirtyRevision=1 means "needs push at first chance".
     */
    suspend fun insertNote(note: NoteEntity, userId: Int = activeUserId()): Long {
        val stamped = note.copy(
            userId = userId,
            serverId = note.serverId ?: java.util.UUID.randomUUID().toString(),
            dirtyRevision = 1,
            lastSyncedRevision = 0
        )
        return noteDao.insertNote(stamped)
    }

    /**
     * V5.3 — every mutation calls this so the push side sees the row.
     * dirtyRevision is bumped over lastSyncedRevision by exactly 1 (or
     * more if the caller needs a stronger signal — caller just bumps
     * again before [noteDao.markSynced] is called from the sync repo).
     *
     * B12 — this variant updates `updatedAt`, so the note jumps to the
     * top of any "ORDER BY updatedAt DESC" list. Reserved for content
     * edits (the editor's [com.example.notes.ui.edit.NoteEditViewModel]
     * and the save path). For non-content flips — favourite, pin,
     * background colour, move-to-trash — call [bumpDirtyMeta] instead
     * so the list ordering stays stable while the user is reorganising.
     */
    private suspend fun bumpDirty(note: NoteEntity, now: Long = System.currentTimeMillis()): NoteEntity {
        val bumped = note.copy(
            dirtyRevision = note.dirtyRevision + 1,
            updatedAt = now
        )
        noteDao.updateNote(bumped)
        return bumped
    }

    /**
     * B12 — same dirty-bump semantics as [bumpDirty] but does NOT
     * touch `updatedAt`. The note still syncs on the next push
     * (dirtyRevision > lastSyncedRevision) but its list position is
     * unchanged, so toggling the star on five notes in a row doesn't
     * shuffle them.
     */
    private suspend fun bumpDirtyMeta(note: NoteEntity): NoteEntity {
        val bumped = note.copy(dirtyRevision = note.dirtyRevision + 1)
        noteDao.updateNote(bumped)
        return bumped
    }

    suspend fun updateNote(note: NoteEntity) = bumpDirty(note)

    suspend fun deleteNote(note: NoteEntity) {
        // V5.3 — soft-delete (BUG 2 fix). The row stays around until
        // the server has acknowledged the tombstone (serverChangeId > 0).
        noteDao.softDeleteNote(note.id, System.currentTimeMillis())
    }

    /**
     * V5.3 — purge once the server has confirmed the soft-delete
     * (called after a successful sync round-trip).
     */
    suspend fun purgeHardDelete(id: Long) = noteDao.purgeNoteById(id)

    suspend fun toggleFavorite(note: NoteEntity) = bumpDirtyMeta(
        note.copy(isFavorite = !note.isFavorite)
    )

    suspend fun togglePin(note: NoteEntity) = bumpDirtyMeta(
        note.copy(isPinned = !note.isPinned)
    )

    /**
     * V5.3 — moving to trash still flips the user-facing flag, but the
     * actual delete-from-server happens via a dedicated
     * `syncRepository.deleteOnServer` call from the list VM.
     *
     * B12 — uses [bumpDirtyMeta] so the trashed note keeps its
     * position in the "All Notes" view (which is already filtered by
     * `isTrashed = 0`); the next push will tell the server the row is
     * trashed so other devices' trash views pick it up.
     */
    suspend fun moveToTrash(note: NoteEntity) = bumpDirtyMeta(
        note.copy(isTrashed = true, deletedAt = null)
    )

    suspend fun restoreFromTrash(note: NoteEntity) = bumpDirtyMeta(
        note.copy(isTrashed = false, deletedAt = null)
    )
}
