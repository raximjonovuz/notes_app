package com.example.notes.data.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * V5.2.3 — direct unit test for the
 * [NotesSyncRepository.hasPendingLocalImage] filter.
 *
 * The filter is the gate that prevents
 * [NotesSyncRepository.pushLocalChangesInternal] from broadcasting a
 * device-local `file://<filesDir>/img_…` URI to the server before the
 * editor's background upload has rewritten it with the public R2 URL.
 *
 * We exercise the helper directly because the integration path also
 * depends on `ConnectivityManager.isOnline()` and the auth store, which
 * are noisy in JVM unit tests. The filter is the actual logic we'd like
 * to lock down; the integration paths are smoke-tested manually on
 * device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = com.example.notes.TestNotesApplication::class)
class PushDeferLocalImageTest {

    private lateinit var context: Context
    private lateinit var repo: NotesSyncRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // We don't exercise the network path here — see class kdoc.
        repo = NotesSyncRepository(noteDao = StubDao(), context = context)
    }

    @Test
    fun `localFileUriPrefix is exactly file plus filesDir plus slash`() {
        // The prefix is the contract the editor relies on. Pin it down so a
        // refactor of [Context.getFilesDir] formatting doesn't silently
        // disable the deferral.
        assertEquals("file://${context.filesDir.absolutePath}/", repo.localFileUriPrefix)
    }

    @Test
    fun `note whose image URI is the local filesDir is deferred`() {
        val localUri = "file://${context.filesDir.absolutePath}/img_pending.png"
        val content = "\n|||IMG:$localUri|||\n"
        assertTrue(
            "an in-flight, editor-inserted image URI must flag the note for deferral",
            repo.hasPendingLocalImage(content)
        )
    }

    @Test
    fun `note whose only image URI is the public R2 URL is not deferred`() {
        val publicUrl = "https://notes-backend.raximjonozod1727.workers.dev/files/uploads/42/x.png"
        val content = "\n|||IMG:$publicUrl|||\n"
        assertFalse(
            "once the URI is rewritten to the public R2 URL, sync must push the row",
            repo.hasPendingLocalImage(content)
        )
    }

    @Test
    fun `text-only note is not deferred`() {
        assertFalse(
            "a note with no inline images must always be eligible to push",
            repo.hasPendingLocalImage(
                "<0|0|0|0>Ssome random text that contains the word file:// but is not a uri"
            )
        )
    }

    @Test
    fun `note with both a local and a public image is still deferred`() {
        // Until the editor has rewritten ALL inline image URIs the note
        // is unsafe to push — a pull from another device would still
        // expose the local one.
        val localUri = "file://${context.filesDir.absolutePath}/img_pending.png"
        val publicUrl = "https://notes-backend.raximjonozod1727.workers.dev/files/uploads/42/x.png"
        val content = "\n|||IMG:$publicUrl|||\n$localUri trailing text"
        assertTrue(
            "mixed-content notes with any pending local URI must still defer",
            repo.hasPendingLocalImage(content)
        )
    }

    @Test
    fun `file URL from a different directory is not deferred`() {
        // The filter is intentionally narrow — it only matches the exact
        // `file://<our filesDir>/` prefix. Anything else (foreign files,
        // unrelated `file://` paths) is treated like any other URI.
        val otherLocal = "file:///data/user/0/com.example.otherapp/files/img.png"
        val content = "\n|||IMG:$otherLocal|||\n"
        assertFalse(
            "a foreign file:// URI must not match the editor-inserted prefix",
            repo.hasPendingLocalImage(content)
        )
    }

    /**
     * The DAO is never touched by these tests — we stop at the helper.
     * Returning a stub keeps [NotesSyncRepository]'s constructor happy
     * without dragging a full DAO fake into this pure-logic test.
     */
    private class StubDao : com.example.notes.data.local.NoteDao {
        override suspend fun insertNote(note: com.example.notes.data.local.NoteEntity): Long = 0L
        override suspend fun updateNote(note: com.example.notes.data.local.NoteEntity) {}
        override suspend fun deleteNote(note: com.example.notes.data.local.NoteEntity) {}
        override suspend fun deleteNoteById(id: Long) {}
        override fun getAllNotes(userId: Int) =
            kotlinx.coroutines.flow.flowOf(emptyList<com.example.notes.data.local.NoteEntity>())
        override suspend fun getNoteById(id: Long) = null
        override fun searchNotes(query: String, userId: Int) =
            kotlinx.coroutines.flow.flowOf(emptyList<com.example.notes.data.local.NoteEntity>())
        override fun getFavoriteNotes(userId: Int) =
            kotlinx.coroutines.flow.flowOf(emptyList<com.example.notes.data.local.NoteEntity>())
        override fun searchFavoriteNotes(query: String, userId: Int) =
            kotlinx.coroutines.flow.flowOf(emptyList<com.example.notes.data.local.NoteEntity>())
        override fun getTrashedNotes(userId: Int) =
            kotlinx.coroutines.flow.flowOf(emptyList<com.example.notes.data.local.NoteEntity>())
        override fun searchTrashedNotes(query: String, userId: Int) =
            kotlinx.coroutines.flow.flowOf(emptyList<com.example.notes.data.local.NoteEntity>())
        override fun getNoteCount(userId: Int) = kotlinx.coroutines.flow.flowOf(0)
        override fun getFavoriteCount(userId: Int) = kotlinx.coroutines.flow.flowOf(0)
        override fun getTrashedCount(userId: Int) = kotlinx.coroutines.flow.flowOf(0)
        override suspend fun getNoteByServerId(serverId: String) = null
        override suspend fun findByContentAndUser(title: String, content: String, userId: Int) = null
        override suspend fun getNotesWithServerId(): List<com.example.notes.data.local.NoteEntity> = emptyList()
        override suspend fun getNotesWithoutServerId(userId: Int): List<com.example.notes.data.local.NoteEntity> = emptyList()
        override suspend fun getNotesToPush(userId: Int): List<com.example.notes.data.local.NoteEntity> = emptyList()
        override suspend fun migrateAnonymousNotesToUser(userId: Int): Int = 0
    }
}
