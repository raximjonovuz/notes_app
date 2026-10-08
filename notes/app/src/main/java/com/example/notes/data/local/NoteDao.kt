package com.example.notes.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    // -----------------------------------------------------------------
    // V3.2: every read query now takes an :userId parameter so each
    // signed-in account sees only its own notes. 0 is reserved for
    // anonymous / pre-login rows.
    // -----------------------------------------------------------------

    // Active (not trashed) notes — V4.5 pinned-first ordering.
    @Query("SELECT * FROM notes WHERE isTrashed = 0 AND userId = :userId ORDER BY isPinned DESC, updatedAt DESC")
    fun getAllNotes(userId: Int): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    suspend fun getNoteById(id: Long): NoteEntity?

    // V4.4: search now matches against both the title and the (serialized)
    // content. The content column holds the V2.6 `<s|a|l|k>S<ranges>body`
    // header so a substring match will pick up most notes that contain
    // the query in their text — header noise is rare in practice and
    // not a false-positive risk because the user types the same
    // characters they want to find.
    @Query("SELECT * FROM notes WHERE (title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%') AND isTrashed = 0 AND userId = :userId ORDER BY isPinned DESC, updatedAt DESC")
    fun searchNotes(query: String, userId: Int): Flow<List<NoteEntity>>

    // Favorites
    @Query("SELECT * FROM notes WHERE isFavorite = 1 AND isTrashed = 0 AND userId = :userId ORDER BY isPinned DESC, updatedAt DESC")
    fun getFavoriteNotes(userId: Int): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE (title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%') AND isFavorite = 1 AND isTrashed = 0 AND userId = :userId ORDER BY isPinned DESC, updatedAt DESC")
    fun searchFavoriteNotes(query: String, userId: Int): Flow<List<NoteEntity>>

    // Trash
    @Query("SELECT * FROM notes WHERE isTrashed = 1 AND userId = :userId ORDER BY isPinned DESC, updatedAt DESC")
    fun getTrashedNotes(userId: Int): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE (title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%') AND isTrashed = 1 AND userId = :userId ORDER BY isPinned DESC, updatedAt DESC")
    fun searchTrashedNotes(query: String, userId: Int): Flow<List<NoteEntity>>

    // Counts
    @Query("SELECT COUNT(*) FROM notes WHERE isTrashed = 0 AND userId = :userId")
    fun getNoteCount(userId: Int): Flow<Int>

    @Query("SELECT COUNT(*) FROM notes WHERE isFavorite = 1 AND isTrashed = 0 AND userId = :userId")
    fun getFavoriteCount(userId: Int): Flow<Int>

    @Query("SELECT COUNT(*) FROM notes WHERE isTrashed = 1 AND userId = :userId")
    fun getTrashedCount(userId: Int): Flow<Int>

    // CRUD — insert/update are userId-aware so the call-site must provide it.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: NoteEntity): Long

    @Update
    suspend fun updateNote(note: NoteEntity)

    @Delete
    suspend fun deleteNote(note: NoteEntity)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteNoteById(id: Long)

    // V3.1: serverId lookup — used by NotesSyncRepository to merge a server
    // response into the local database by matching on the server-side UUID.
    @Query("SELECT * FROM notes WHERE serverId = :serverId LIMIT 1")
    suspend fun getNoteByServerId(serverId: String): NoteEntity?

    // V5.0.2 — secondary lookup used by pull sync to deduplicate notes
    // whose local serverId was never populated (e.g. push failed first
    // then a later pull tried to insert the same row). Without this
    // every pull would spawn a duplicate row.
    @Query("SELECT * FROM notes WHERE title = :title AND content = :content AND userId = :userId LIMIT 1")
    suspend fun findByContentAndUser(title: String, content: String, userId: Int): NoteEntity?

    // V3.1: bulk fetch for any local notes that have already been linked to
    // a server UUID. Used during a future push pass to enumerate candidates
    // that may need to be re-uploaded.
    @Query("SELECT * FROM notes WHERE serverId IS NOT NULL")
    suspend fun getNotesWithServerId(): List<NoteEntity>

    // V4.3.1: notes the editor has saved locally but the push-sync
    // pass hasnt yet uploaded. Returned in MRU order so the most
    // recent edits are pushed first when the network comes back.
    @Query("SELECT * FROM notes WHERE serverId IS NULL AND userId = :userId ORDER BY updatedAt DESC")
    suspend fun getNotesWithoutServerId(userId: Int): List<NoteEntity>

    // V4.4: union of "never synced" and "synced but locally edited since".
    // `serverId IS NULL OR serverId IS NOT NULL` is the full table; we
    // depend on the DTO carrying the latest local state on every push.
    @Query("SELECT * FROM notes WHERE userId = :userId")
    suspend fun getNotesToPush(userId: Int): List<NoteEntity>

    // V4.3.1: when a user signs in for the first time, any notes that
    // were created while no user was signed in (userId = 0, "anonymous")
    // would otherwise be hidden by the per-user list filter. Migrate
    // them to the new active user so they become visible in the list.
    // Returns the number of rows updated.
    @Query("UPDATE notes SET userId = :userId WHERE userId = 0")
    suspend fun migrateAnonymousNotesToUser(userId: Int): Int
}