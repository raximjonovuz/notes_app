package com.example.notes.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * V5.3 — every read query now filters `deletedAt IS NULL` (soft-delete),
 * and the push-side query is keyed on `dirtyRevision > lastSyncedRevision`
 * instead of `serverId IS NULL` (BUG 1 fix). The pull cursor moved to
 * `serverChangeId` (BUG 4 fix).
 */
@Dao
interface NoteDao {

    /** Active (not trashed, not soft-deleted) notes — pinned first. */
    @Query(
        "SELECT * FROM notes " +
        "WHERE isTrashed = 0 AND deletedAt IS NULL AND userId = :userId " +
        "ORDER BY isPinned DESC, updatedAt DESC"
    )
    fun getAllNotes(userId: Int): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    suspend fun getNoteById(id: Long): NoteEntity?

    @Query(
        "SELECT * FROM notes WHERE " +
        "(title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%') " +
        "AND isTrashed = 0 AND deletedAt IS NULL AND userId = :userId " +
        "ORDER BY isPinned DESC, updatedAt DESC"
    )
    fun searchNotes(query: String, userId: Int): Flow<List<NoteEntity>>

    @Query(
        "SELECT * FROM notes WHERE isFavorite = 1 AND isTrashed = 0 AND deletedAt IS NULL AND userId = :userId " +
        "ORDER BY isPinned DESC, updatedAt DESC"
    )
    fun getFavoriteNotes(userId: Int): Flow<List<NoteEntity>>

    @Query(
        "SELECT * FROM notes WHERE (title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%') " +
        "AND isFavorite = 1 AND isTrashed = 0 AND deletedAt IS NULL AND userId = :userId " +
        "ORDER BY isPinned DESC, updatedAt DESC"
    )
    fun searchFavoriteNotes(query: String, userId: Int): Flow<List<NoteEntity>>

    @Query(
        "SELECT * FROM notes WHERE isTrashed = 1 AND deletedAt IS NULL AND userId = :userId " +
        "ORDER BY isPinned DESC, updatedAt DESC"
    )
    fun getTrashedNotes(userId: Int): Flow<List<NoteEntity>>

    @Query(
        "SELECT * FROM notes WHERE (title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%') " +
        "AND isTrashed = 1 AND deletedAt IS NULL AND userId = :userId " +
        "ORDER BY isPinned DESC, updatedAt DESC"
    )
    fun searchTrashedNotes(query: String, userId: Int): Flow<List<NoteEntity>>

    @Query("SELECT COUNT(*) FROM notes WHERE isTrashed = 0 AND deletedAt IS NULL AND userId = :userId")
    fun getNoteCount(userId: Int): Flow<Int>

    @Query("SELECT COUNT(*) FROM notes WHERE isFavorite = 1 AND isTrashed = 0 AND deletedAt IS NULL AND userId = :userId")
    fun getFavoriteCount(userId: Int): Flow<Int>

    @Query("SELECT COUNT(*) FROM notes WHERE isTrashed = 1 AND deletedAt IS NULL AND userId = :userId")
    fun getTrashedCount(userId: Int): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: NoteEntity): Long

    @Update
    suspend fun updateNote(note: NoteEntity)

    /**
     * V5.3 — hard-local delete is no longer used for the "delete" button.
     * It now sets a tombstone via [softDeleteNote] which keeps the row
     * around until the next push confirms the server.
     */
    @Query("UPDATE notes SET deletedAt = :now, dirtyRevision = dirtyRevision + 1, updatedAt = :now " +
           "WHERE id = :id")
    suspend fun softDeleteNote(id: Long, now: Long)

    /** V5.3 — used by the "burn after server confirms" cleanup. */
    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteNoteById(id: Long)

    @Query("SELECT * FROM notes WHERE serverId = :serverId LIMIT 1")
    suspend fun getNoteByServerId(serverId: String): NoteEntity?

    /** V5.3 — content+title backfill lookup, used by legacy path. */
    @Query("SELECT * FROM notes WHERE title = :title AND content = :content AND userId = :userId LIMIT 1")
    suspend fun findByContentAndUser(title: String, content: String, userId: Int): NoteEntity?

    /**
     * V5.3 — push drain. Returns every row that has local changes the
     * server hasn't acknowledged yet, regardless of whether it was ever
     * synced. BUG 1 fix: previously this was `serverId IS NULL` which
     * silently skipped trash/pin/color flips on already-synced notes.
     *
     * - dirtyRevision > 0 means "the local state has diverged from
     *   lastSyncedRevision". Every mutation in NoteRepository bumps it.
     * - We exclude tombstones whose serverChangeId already reflects the
     *   soft-delete (cleaned up below by [purgeSoftDeleted]).
     */
    @Query(
        "SELECT * FROM notes WHERE userId = :userId " +
        "AND dirtyRevision > lastSyncedRevision " +
        "AND (deletedAt IS NULL OR serverChangeId = 0) " +
        "ORDER BY updatedAt DESC"
    )
    suspend fun getDirtyNotes(userId: Int): List<NoteEntity>

    /**
     * V5.3 — rows whose soft-delete has been confirmed by the server
     * (serverChangeId > 0 means we already saw the tombstone in a
     * pull). Once the server has acknowledged a delete, the local row
     * can be dropped permanently without risk of ghost-note resurrection.
     */
    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun purgeNoteById(id: Long)

    @Query("UPDATE notes SET userId = :userId WHERE userId = 0")
    suspend fun migrateAnonymousNotesToUser(userId: Int): Int

    /**
     * V5.3 — record that a successful push happened. After this returns,
     * the WHERE dirtyRevision > lastSyncedRevision filter will not pick
     * the row up again until the user mutates it (which bumps
     * dirtyRevision back above lastSyncedRevision).
     *
     * `syncedRev` must be the dirtyRevision value that was on the row
     * when the push started — captured by the caller.
     */
    @Query(
        "UPDATE notes SET lastSyncedRevision = :syncedRev, dirtyRevision = :syncedRev, " +
        "serverChangeId = :serverChangeId, serverUpdatedAt = :serverUpdatedAt, " +
        "baseTitle = title, baseContent = content WHERE id = :localId"
    )
    suspend fun markSynced(
        localId: Long,
        syncedRev: Int,
        serverChangeId: Long,
        serverUpdatedAt: Long
    )

    /** V5.3 — fix-up: bump dirtyRevision on a row (used by toEntity → insert). */
    @Query("UPDATE notes SET dirtyRevision = dirtyRevision + 1, updatedAt = :now WHERE id = :id")
    suspend fun bumpDirty(id: Long, now: Long)

    /** V5.3 — used by BackgroundSyncWorker once a day to push pending deltas. */
    @Query("SELECT COUNT(*) FROM notes WHERE userId = :userId AND dirtyRevision > lastSyncedRevision")
    suspend fun countDirtyNotes(userId: Int): Int

    /** V5.3 — used by the cold-start flush (BUG 8). */
    @Query("SELECT * FROM notes WHERE userId = :userId AND dirtyRevision > lastSyncedRevision LIMIT :limit")
    suspend fun getDirtyNotesBounded(userId: Int, limit: Int): List<NoteEntity>

    /**
     * V5.3 — backup export snapshot. Returns a one-shot [List] (not a
     * Flow) of every live note for the user, ordered as the list screen
     * shows it. Tombstoned rows are excluded.
     */
    @Query(
        "SELECT * FROM notes WHERE userId = :userId AND deletedAt IS NULL " +
        "ORDER BY isPinned DESC, updatedAt DESC"
    )
    suspend fun getAllNotesSnapshot(userId: Int): List<NoteEntity>
}
