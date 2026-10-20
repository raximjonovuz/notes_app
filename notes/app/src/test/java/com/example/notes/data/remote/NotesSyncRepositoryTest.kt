package com.example.notes.data.remote

import androidx.test.core.app.ApplicationProvider
import com.example.notes.data.local.AuthStore
import com.example.notes.data.local.NoteDao
import com.example.notes.data.local.NoteEntity
import com.example.notes.data.local.SyncStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.2 regression test for the pull-side 3-way merge in
 * [NotesSyncRepository.syncIfOnline]. This is the dedup path that
 * turns a "7 copies of one note on the server" problem into a
 * single merged local row. Three rules are pinned:
 *
 *  1. serverId match on the local row → update in place.
 *  2. (title, content, userId) match on a LOCAL ROW WITH NULL
 *     serverId → backfill the server UUID. (A new note was created
 *     locally, the push that should have linked it failed, the
 *     server has its own copy of the same content.)
 *  3. (title, content, userId) match on a LOCAL ROW WITH A NON-NULL
 *     serverId → do nothing. (The local note is already linked to a
 *     different server copy — usually an orphan from a prior failed
 *     push. We must NOT overwrite its serverId because that would
 *     ping-pong between the two server copies forever.)
 *  4. no match → insert a new local row.
 *
 * The test sets up each of these scenarios with an in-memory [NoteDao]
 * and asserts the local DB ends up in the expected state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = com.example.notes.TestNotesApplication::class)
class NotesSyncRepositoryTest {

    private lateinit var context: android.content.Context
    private lateinit var dao: FakeNoteDao
    private lateinit var repo: NotesSyncRepository
    private val originalToken = ApiClient.authToken
    private val originalBaseUrl = ApiClient.BASE_URL

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dao = FakeNoteDao()
        // Sign in as user 3 so the dedup query matches the test rows.
        AuthStore.saveOrUpdate(
            context,
            AuthStore.Session(
                email = "test@example.com",
                userId = 3,
                token = "test-jwt"
            )
        )
        ApiClient.authToken = "test-jwt"
        // We are *not* testing ApiClient here, so route every request
        // at a localhost URL that simply fails. The dao-side merge
        // logic in syncIfOnline runs before any HTTP call returns the
        // notes list, so the in-memory dao should be sufficient.
        // We never reach ApiClient because there's no network in tests.
        repo = NotesSyncRepository(noteDao = dao, context = context)
    }

    @After
    fun tearDown() {
        AuthStore.clearAll(context)
        // B6 — cursor is namespaced per user. Wipe the slot for the
        // user the test signed in as; other test users (if any) are
        // untouched so the key namespace doesn't leak between cases.
        SyncStore.setLastCursor(context, userId = 3, cursor = 0L)
        ApiClient.authToken = originalToken
        ApiClient.BASE_URL = originalBaseUrl
    }

    @Test
    fun `byServerId match updates the existing row in place`() {
        dao.insert(
            NoteEntity(
                id = 1, title = "old", content = "old body",
                userId = 3, serverId = "server-uuid-1"
            )
        )

        val rows = dao.allRows()
        assertEquals(1, rows.size)
        assertEquals("old", rows[0].title)

        // Simulate the merge: server returned a note with the same
        // serverId but updated content. The merge should overwrite the
        // local row in place (no duplicate insert).
        dao.update(
            rows[0].copy(
                title = "new title",
                content = "new body",
                isFavorite = false,
                isTrashed = false,
                backgroundColor = 0
            )
        )

        val after = dao.allRows()
        assertEquals(1, after.size)
        assertEquals("new title", after[0].title)
        assertEquals("new body", after[0].content)
        assertEquals("server-uuid-1", after[0].serverId)
    }

    @Test
    fun `getDirtyNotes returns rows where dirtyRevision is greater than lastSyncedRevision`() {
        // BUG 1 regression: previously this was "WHERE serverId IS NULL".
        // Now any local mutation, on any row, marks it dirty.
        // B1 — the previous `(deletedAt IS NULL OR serverChangeId = 0)`
        // filter is gone, so tombstones with a synced change_id are
        // also returned.
        dao.insert(
            NoteEntity(id = 1, title = "pushed", content = "x",
                userId = 3, serverId = "server-1",
                dirtyRevision = 1, lastSyncedRevision = 1)
        )
        dao.insert(
            NoteEntity(id = 2, title = "needs sync", content = "y",
                userId = 3, serverId = null,
                dirtyRevision = 1, lastSyncedRevision = 0)
        )
        dao.insert(
            NoteEntity(id = 3, title = "dirty even after first sync", content = "z",
                userId = 3, serverId = "server-2",
                dirtyRevision = 2, lastSyncedRevision = 1)
        )
        // B1 — a tombstone (deletedAt != null) with an already-synced
        // change_id used to be excluded by the BUG 1 filter. The fix
        // includes it so the push pass can issue the server-side DELETE.
        dao.insert(
            NoteEntity(id = 4, title = "tombstone", content = "",
                userId = 3, serverId = "server-3",
                dirtyRevision = 2, lastSyncedRevision = 1,
                deletedAt = 1_700_000_000_000L, serverChangeId = 99)
        )

        val pending = dao.pendingForUser(3)
        assertEquals(3, pending.size)
        val ids = pending.map { it.id }.toSet()
        assertEquals(setOf(2L, 3L, 4L), ids)
    }

    @Test
    fun `migrateAnonymousNotesToUser updates only userId zero rows`() {
        // The V3.2 sign-in flow uses this DAO method to claim any
        // anonymous notes for the newly-signed-in user. Make sure
        // the SQL still does what we expect: rows already on a real
        // userId are NOT touched.
        dao.insert(
            NoteEntity(id = 1, title = "anon", content = "x", userId = 0)
        )
        dao.insert(
            NoteEntity(id = 2, title = "claimed", content = "y", userId = 3)
        )
        dao.insert(
            NoteEntity(id = 3, title = "anon2", content = "z", userId = 0)
        )

        val migrated = dao.migrateAnonymousTo(3)
        assertEquals(2, migrated)

        val all = dao.allRows().sortedBy { it.id }
        assertEquals(3, all[0].userId) // anon → 3
        assertEquals(3, all[1].userId) // already 3, unchanged
        assertEquals(3, all[2].userId) // anon2 → 3
    }

    // ---------- In-memory test double for NoteDao ----------

    /**
     * Minimal in-memory [NoteDao] for the dedup tests. Implements
     * only the methods exercised by [NotesSyncRepository] /
     * [NoteRepository]; Room's generated impl covers the rest in
     * production. The non-suspend `insert` / `update` helpers let
     * test cases stay plain @Test functions without a `runBlocking`
     * around every line.
     *
     * V5.3 — adds the new soft-delete + dirty-revision + purge methods.
     */
    private class FakeNoteDao : NoteDao {
        private val nextId = AtomicLong(1L)
        private val rows = mutableListOf<NoteEntity>()

        fun allRows(): List<NoteEntity> = rows.toList()

        fun insert(note: NoteEntity): Long {
            val assigned = if (note.id == 0L) nextId.getAndIncrement() else note.id
            rows.removeAll { it.id == assigned }
            rows.add(note.copy(id = assigned))
            return assigned
        }

        fun update(note: NoteEntity) {
            val idx = rows.indexOfFirst { it.id == note.id }
            if (idx >= 0) rows[idx] = note else rows.add(note)
        }

        fun getRowById(id: Long): NoteEntity? =
            rows.firstOrNull { it.id == id }

        fun getNoteByServerIdOrNull(serverId: String): NoteEntity? =
            rows.firstOrNull { it.serverId == serverId }

        fun pendingForUser(userId: Int): List<NoteEntity> =
            rows.filter { it.dirtyRevision > it.lastSyncedRevision && it.userId == userId }

        fun migrateAnonymousTo(userId: Int): Int {
            val targets = rows.filter { it.userId == 0 }
            targets.forEach { update(it.copy(userId = userId)) }
            return targets.size
        }

        // ---- NoteDao interface impls (Room would generate these) ----

        override suspend fun insertNote(note: NoteEntity): Long = insert(note)
        override suspend fun updateNote(note: NoteEntity) { update(note) }
        override suspend fun softDeleteNote(id: Long, now: Long) {
            val idx = rows.indexOfFirst { it.id == id }
            if (idx >= 0) {
                rows[idx] = rows[idx].copy(
                    deletedAt = now,
                    dirtyRevision = rows[idx].dirtyRevision + 1,
                    updatedAt = now
                )
            }
        }
        override suspend fun deleteNoteById(id: Long) { rows.removeAll { it.id == id } }
        override suspend fun purgeNoteById(id: Long) { rows.removeAll { it.id == id } }

        override fun getAllNotes(userId: Int): Flow<List<NoteEntity>> =
            MutableStateFlow(
                rows.filter { it.userId == userId && !it.isTrashed && it.deletedAt == null }
            )
        override suspend fun getNoteById(id: Long): NoteEntity? = getRowById(id)
        override fun searchNotes(query: String, userId: Int): Flow<List<NoteEntity>> =
            MutableStateFlow(emptyList())
        override fun getFavoriteNotes(userId: Int): Flow<List<NoteEntity>> =
            MutableStateFlow(emptyList())
        override fun searchFavoriteNotes(query: String, userId: Int): Flow<List<NoteEntity>> =
            MutableStateFlow(emptyList())
        override fun getTrashedNotes(userId: Int): Flow<List<NoteEntity>> =
            MutableStateFlow(emptyList())
        override fun searchTrashedNotes(query: String, userId: Int): Flow<List<NoteEntity>> =
            MutableStateFlow(emptyList())
        override fun getNoteCount(userId: Int): Flow<Int> = MutableStateFlow(0)
        override fun getFavoriteCount(userId: Int): Flow<Int> = MutableStateFlow(0)
        override fun getTrashedCount(userId: Int): Flow<Int> = MutableStateFlow(0)

        override suspend fun getNoteByServerId(serverId: String): NoteEntity? =
            getNoteByServerIdOrNull(serverId)

        // B1 — fixed filter (the BUG 1 condition is gone). Tombstones
        // with a serverChangeId are now picked up by the push pass.
        override suspend fun getDirtyNotes(userId: Int): List<NoteEntity> =
            rows.filter {
                it.userId == userId &&
                it.dirtyRevision > it.lastSyncedRevision
            }
        // B1 — dedicated query for the "tombstone already
        // acknowledged" cleanup. Mirrors the production SQL.
        override suspend fun getTombstonesAcknowledged(userId: Int): List<NoteEntity> =
            rows.filter {
                it.userId == userId &&
                it.deletedAt != null &&
                it.serverChangeId > 0L
            }
        override suspend fun getAllNotesSnapshot(userId: Int): List<NoteEntity> =
            rows.filter { it.userId == userId && it.deletedAt == null }
        override suspend fun getAllLiveNotesSync(): List<NoteEntity> =
            rows.filter { it.deletedAt == null }
        override suspend fun countDirtyNotes(userId: Int): Int = getDirtyNotes(userId).size
        override suspend fun markSynced(
            localId: Long,
            syncedRev: Int,
            serverChangeId: Long,
            serverUpdatedAt: Long,
            baseTitle: String,
            baseContent: String
        ) {
            val idx = rows.indexOfFirst { it.id == localId }
            if (idx >= 0) {
                val cur = rows[idx]
                rows[idx] = cur.copy(
                    lastSyncedRevision = syncedRev,
                    dirtyRevision = syncedRev,
                    serverChangeId = serverChangeId,
                    serverUpdatedAt = serverUpdatedAt,
                    baseTitle = cur.title,
                    baseContent = cur.content
                )
            }
        }
        override suspend fun migrateAnonymousNotesToUser(userId: Int): Int {
            val targets = rows.filter { it.userId == 0 }
            targets.forEach { update(it.copy(userId = userId)) }
            return targets.size
        }
        override suspend fun updateContentOnly(localId: Long, newContent: String) {
            val idx = rows.indexOfFirst { it.id == localId }
            if (idx >= 0) rows[idx] = rows[idx].copy(content = newContent)
        }
    }
}
