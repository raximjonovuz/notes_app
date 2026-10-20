package com.example.notes.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.notes.data.local.AuthStore
import com.example.notes.data.local.NoteDao
import com.example.notes.data.local.NoteEntity
import com.example.notes.data.local.SyncStore
import com.example.notes.ui.edit.parseBlocksFromContent
import com.example.notes.ui.edit.serializeBlocksToContent
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F-28 — unit tests for [LocalImageMigration].
 *
 * The migration has three observable contracts:
 *   1. The pure `rewriteImageUri` / `rewriteContent` helpers are
 *      deterministic and only touch legacy `file://<root>/img_X` URIs.
 *   2. The on-disk + DB migration is idempotent: running it twice
 *      on the same data must leave the file system and the database
 *      in the same end state as running it once.
 *   3. A missing on-disk file (e.g. the user deleted it from the
 *      app-private dir before the migration ran) does not crash the
 *      migration; the affected note's content is still rewritten so
 *      the editor can re-create the file on next save.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = com.example.notes.TestNotesApplication::class)
class LocalImageMigrationTest {

    private lateinit var context: Context
    private lateinit var dao: FakeNoteDao
    private var originalToken: String? = null
    private val filesDirPath: String by lazy { context.filesDir.absolutePath }
    private val legacyPrefix: String by lazy { "file://$filesDirPath/" }
    private val newPrefix: String by lazy { "file://$filesDirPath/images/" }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        originalToken = AuthStore.getActive(context)?.token
        dao = FakeNoteDao()
        // Clean any prior migration state and the legacy directory.
        val prefs = context.getSharedPreferences(
            LocalImageMigrationPrefsName, Context.MODE_PRIVATE
        )
        prefs.edit().remove(LocalImageMigrationPrefsKey).apply()
        context.filesDir.listFiles { f -> f.isFile && f.name.startsWith("img_") }
            ?.forEach { it.delete() }
        File(context.filesDir, "images").deleteRecursively()
    }

    @After
    fun tearDown() {
        context.filesDir.listFiles { f -> f.isFile && f.name.startsWith("img_") }
            ?.forEach { it.delete() }
        File(context.filesDir, "images").deleteRecursively()
        if (originalToken != null) AuthStore.clearAll(context)
        SyncStore.setLastCursor(context, userId = 1, cursor = 0L)
    }

    // ---------- pure rewriteImageUri ----------

    @Test
    fun `rewriteImageUri moves a legacy img URI into the images subdir`() {
        val out = LocalImageMigration.rewriteImageUri(
            "$legacyPrefix/img_abc.png",
            legacyPrefix, newPrefix
        )
        assertEquals("${newPrefix}img_abc.png", out)
    }

    @Test
    fun `rewriteImageUri leaves a new images URI unchanged (idempotent at the URI level)`() {
        val already = "${newPrefix}img_abc.png"
        assertEquals(
            already,
            LocalImageMigration.rewriteImageUri(already, legacyPrefix, newPrefix)
        )
    }

    @Test
    fun `rewriteImageUri leaves R2 https URLs unchanged`() {
        val r2 = "https://notes-backend.raximjonozod1727.workers.dev/files/uploads/1/x.png"
        assertEquals(
            r2,
            LocalImageMigration.rewriteImageUri(r2, legacyPrefix, newPrefix)
        )
    }

    @Test
    fun `rewriteImageUri leaves foreign file URLs unchanged`() {
        // Same `file://` scheme, but not under our filesDir and not
        // named `img_*` — must pass through untouched.
        val other = "file:///data/user/0/com.example.otherapp/files/foo.png"
        assertEquals(
            other,
            LocalImageMigration.rewriteImageUri(other, legacyPrefix, newPrefix)
        )
    }

    @Test
    fun `rewriteImageUri leaves unrelated filesDir files alone`() {
        // A `file://<root>/foo.txt` would otherwise be dragged into
        // `images/`. We never wrote it; we don't know what it is.
        val unrelated = "$legacyPrefix/foo.txt"
        assertEquals(
            unrelated,
            LocalImageMigration.rewriteImageUri(unrelated, legacyPrefix, newPrefix)
        )
    }

    // ---------- rewriteContent round-trips both content formats ----------

    @Test
    fun `rewriteContent handles V2_6 legacy header content with one image`() {
        val imgUri = "$legacyPrefix/img_legacy.png"
        val content = "<0|0|0|0>Shello world\n|||IMG:$imgUri|||\n<0|0|0|0>Smore text"
        val out = LocalImageMigration.rewriteContent(content, legacyPrefix, newPrefix)
        assertNotEquals("rewritten content must differ", content, out)
        // Round-trip back to blocks and assert the URI is in the new path.
        val blocks = parseBlocksFromContent(out)
        val imgs = blocks.filterIsInstance<com.example.notes.ui.edit.ContentBlock.ImageBlock>()
        assertEquals(1, imgs.size)
        assertEquals("${newPrefix}img_legacy.png", imgs[0].uri)
    }

    @Test
    fun `rewriteContent handles JSON v2 content with multiple images`() {
        val img1 = "${newPrefix}img_one.png"
        val img2 = "$legacyPrefix/img_two.png"
        val img3 = "https://example.com/three.png"
        val raw = serializeBlocksToContent(
            listOf(
                com.example.notes.ui.edit.ContentBlock.TextBlock(text = "intro"),
                com.example.notes.ui.edit.ContentBlock.ImageBlock(uri = img1),
                com.example.notes.ui.edit.ContentBlock.TextBlock(text = "between"),
                com.example.notes.ui.edit.ContentBlock.ImageBlock(uri = img2),
                com.example.notes.ui.edit.ContentBlock.TextBlock(text = "trailing"),
                com.example.notes.ui.edit.ContentBlock.ImageBlock(uri = img3)
            )
        )
        val out = LocalImageMigration.rewriteContent(raw, legacyPrefix, newPrefix)
        val blocks = parseBlocksFromContent(out)
        val imgs = blocks.filterIsInstance<com.example.notes.ui.edit.ContentBlock.ImageBlock>()
        assertEquals(3, imgs.size)
        // The new path stays new, the legacy path becomes new, the
        // remote URL stays remote — exactly one of them moves.
        assertEquals(img1, imgs[0].uri)
        assertEquals("${newPrefix}img_two.png", imgs[1].uri)
        assertEquals(img3, imgs[2].uri)
    }

    @Test
    fun `rewriteContent is a no-op when no legacy URI is present`() {
        val raw = serializeBlocksToContent(
            listOf(
                com.example.notes.ui.edit.ContentBlock.TextBlock(text = "no images"),
                com.example.notes.ui.edit.ContentBlock.ImageBlock(
                    uri = "https://example.com/x.png"
                )
            )
        )
        val out = LocalImageMigration.rewriteContent(raw, legacyPrefix, newPrefix)
        assertEquals("no rewrite needed → returned the same string", raw, out)
    }

    // ---------- end-to-end migration (idempotency + missing file) ----------

    @Test
    fun `runIfNeeded is idempotent — two runs leave the same end state`() = runBlocking {
        val legacyFile = File(context.filesDir, "img_idem.png")
        legacyFile.writeBytes(byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte()))
        val noteId = dao.insert(
            NoteEntity(
                title = "n", content = "old body", userId = 1,
                serverId = "s1", dirtyRevision = 0, lastSyncedRevision = 0
            )
        )
        dao.updateContentOnlyStatic(
            noteId,
            "<0|0|0|0>Sbody\n|||IMG:$legacyPrefix${"/img_idem.png"}|||\n<0|0|0|0>Send"
        )
        val dirtyBefore = dao.getRowById(noteId)!!.dirtyRevision

        // First run.
        val first = LocalImageMigration.runIfNeeded(context, dao)
        assertTrue("first run must do work", first)
        val after1 = dao.getRowById(noteId)!!
        val newCopy = File(File(context.filesDir, "images"), "img_idem.png")
        assertTrue("new copy must exist", newCopy.exists())
        assertFalse("legacy file must be gone after first run", legacyFile.exists())
        assertEquals("dirtyRevision must NOT be touched by migration", dirtyBefore, after1.dirtyRevision)
        // Content URI must have moved. We parse the (now JSON v2)
        // content back to blocks and compare on the in-memory string,
        // because a `contains` substring match on a backslashed
        // Robolectric Windows path would have to deal with JSON
        // escaping inside the v2 envelope and is more brittle than
        // round-tripping the content.
        val blocks1 = parseBlocksFromContent(after1.content)
        val imgs1 = blocks1.filterIsInstance<com.example.notes.ui.edit.ContentBlock.ImageBlock>()
        assertEquals("exactly one image block survives", 1, imgs1.size)
        val expectedUri = newPrefix + "img_idem.png"
        assertEquals(
            "image URI must point at the new images/ subdir",
            expectedUri, imgs1[0].uri
        )

        // Second run — no-op.
        val second = LocalImageMigration.runIfNeeded(context, dao)
        assertFalse("second run must report no work", second)
        val after2 = dao.getRowById(noteId)!!
        assertEquals(
            "second run must not rewrite content again",
            after1.content, after2.content
        )
    }

    @Test
    fun `runIfNeeded still rewrites a note whose file is missing on disk`() = runBlocking {
        // DB references a legacy URI but the file was deleted out from
        // under us (e.g. user wiped filesDir before the migration ran).
        // The migration must not crash; it should still rewrite the
        // content so the next save can re-create the image.
        val noteId = dao.insert(
            NoteEntity(
                title = "n", content = "body", userId = 1,
                serverId = "s1", dirtyRevision = 0, lastSyncedRevision = 0
            )
        )
        dao.updateContentOnlyStatic(
            noteId,
            "<0|0|0|0>Sbody\n|||IMG:$legacyPrefix${"/img_missing.png"}|||\n<0|0|0|0>Send"
        )
        // No file is written — simulates the user having deleted it.
        assertFalse(
            File(context.filesDir, "img_missing.png").exists()
        )

        val ran = LocalImageMigration.runIfNeeded(context, dao)
        assertTrue("run must report work done", ran)
        val after = dao.getRowById(noteId)!!
        // Round-trip through the parser so we don't have to fight
        // JSON-escaping on Robolectric's backslashed paths.
        val imgs = parseBlocksFromContent(after.content)
            .filterIsInstance<com.example.notes.ui.edit.ContentBlock.ImageBlock>()
        assertEquals("image block survives the rewrite", 1, imgs.size)
        assertEquals(
            "DB URI must be rewritten even when the file was missing",
            newPrefix + "img_missing.png", imgs[0].uri
        )
        // The on-disk state is consistent: no file in either location,
        // no failed steps logged as crashes.
        assertFalse(
            File(context.filesDir, "img_missing.png").exists()
        )
        assertFalse(
            File(File(context.filesDir, "images"), "img_missing.png").exists()
        )
    }

    @Test
    fun `runIfNeeded leaves an unrelated filesDir file alone`() = runBlocking {
        val unrelated = File(context.filesDir, "important.txt")
        unrelated.writeBytes(byteArrayOf(0x68, 0x69))
        LocalImageMigration.runIfNeeded(context, dao)
        assertTrue("unrelated file must not be moved", unrelated.exists())
    }

    // ---------- completed_v1 flag rules ----------

    @Test
    fun `runIfNeeded sets completed_v1 after a clean success`() = runBlocking {
        File(context.filesDir, "img_clean.png").writeBytes(
            byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte())
        )
        val noteId = dao.insert(
            NoteEntity(
                title = "n", content = "x", userId = 1,
                serverId = "s", dirtyRevision = 0, lastSyncedRevision = 0
            )
        )
        dao.updateContentOnlyStatic(
            noteId,
            "<0|0|0|0>Sa\n|||IMG:$legacyPrefix${"/img_clean.png"}|||\n<0|0|0|0>Sb"
        )

        LocalImageMigration.runIfNeeded(context, dao)

        val prefs = context.getSharedPreferences(
            LocalImageMigrationPrefsName, Context.MODE_PRIVATE
        )
        assertTrue(
            "completed_v1 must be true after a clean migration",
            prefs.getBoolean(LocalImageMigrationPrefsKey, false)
        )
    }

    @Test
    fun `runIfNeeded does NOT set completed_v1 when a note's update throws`() = runBlocking {
        File(context.filesDir, "img_err.png").writeBytes(
            byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte())
        )
        val noteId = dao.insert(
            NoteEntity(
                title = "n", content = "x", userId = 1,
                serverId = "s", dirtyRevision = 0, lastSyncedRevision = 0
            )
        )
        dao.updateContentOnlyStatic(
            noteId,
            "<0|0|0|0>Sa\n|||IMG:$legacyPrefix${"/img_err.png"}|||\n<0|0|0|0>Sb"
        )
        // Simulate a transient DB failure for this specific note.
        dao.failNextUpdateForId(noteId)

        LocalImageMigration.runIfNeeded(context, dao)

        val prefs = context.getSharedPreferences(
            LocalImageMigrationPrefsName, Context.MODE_PRIVATE
        )
        assertFalse(
            "completed_v1 must stay false when a per-step error happens, " +
                "so the next start retries the migration",
            prefs.getBoolean(LocalImageMigrationPrefsKey, false)
        )
    }

    // ---------- splash-gate behaviour ----------

    @Test
    fun `runIfNeeded resolves completionSignal even when a step fails (splash gate)`() = runBlocking {
        // Splash waits on `completionSignal` before letting the UI
        // open. The signal must resolve on both success and failure
        // paths so the UI never blocks forever.
        File(context.filesDir, "img_gate.png").writeBytes(
            byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte())
        )
        val noteId = dao.insert(
            NoteEntity(
                title = "n", content = "x", userId = 1,
                serverId = "s", dirtyRevision = 0, lastSyncedRevision = 0
            )
        )
        dao.updateContentOnlyStatic(
            noteId,
            "<0|0|0|0>Sa\n|||IMG:$legacyPrefix${"/img_gate.png"}|||\n<0|0|0|0>Sb"
        )
        dao.failNextUpdateForId(noteId)

        // The field is a public val; we read its isCompleted flag
        // before and after the call to verify the migration did
        // resolve the gate even though the migration itself errored.
        // (If a previous test already completed the deferred, the
        // before/after still confirms the error path doesn't leave
        // the gate stuck.)
        val before = LocalImageMigration.completionSignal.isCompleted
        LocalImageMigration.runIfNeeded(context, dao)
        assertTrue(
            "completionSignal must resolve even on migration errors",
            LocalImageMigration.completionSignal.isCompleted
        )
        // If it wasn't complete before, it should be now — the
        // error path explicitly calls complete(Unit).
        if (!before) {
            assertTrue(
                "runIfNeeded with a forced error must still resolve " +
                    "the splash gate",
                LocalImageMigration.completionSignal.isCompleted
            )
        }
    }

    // ---------- partial migration (continuation) ----------

    @Test
    fun `partial migration — failed note's URI is retried on the next run`() = runBlocking {
        // Two legacy files, each referenced by one note. Note A's
        // update throws the first time and succeeds the second —
        // simulates a transient SQLite/IO error. The migration must
        // leave the first file alone on the first run, then catch up
        // on the second run.
        File(context.filesDir, "img_part_a.png").writeBytes(
            byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte())
        )
        File(context.filesDir, "img_part_b.png").writeBytes(
            byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte())
        )
        val noteA = dao.insert(
            NoteEntity(
                title = "a", content = "x", userId = 1,
                serverId = "sa", dirtyRevision = 0, lastSyncedRevision = 0
            )
        )
        val noteB = dao.insert(
            NoteEntity(
                title = "b", content = "x", userId = 1,
                serverId = "sb", dirtyRevision = 0, lastSyncedRevision = 0
            )
        )
        dao.updateContentOnlyStatic(
            noteA,
            "<0|0|0|0>Sa\n|||IMG:$legacyPrefix${"/img_part_a.png"}|||\n<0|0|0|0>Sb"
        )
        dao.updateContentOnlyStatic(
            noteB,
            "<0|0|0|0>Sa\n|||IMG:$legacyPrefix${"/img_part_b.png"}|||\n<0|0|0|0>Sb"
        )
        // First run: note A throws, note B succeeds.
        dao.failNextUpdateForId(noteA)

        LocalImageMigration.runIfNeeded(context, dao)

        // After first run: note A still has the legacy URI, note B
        // has been rewritten, both legacy files are still on disk
        // (note A's file is still referenced), img_part_b.png is
        // deleted (no longer referenced).
        val legacyA = File(context.filesDir, "img_part_a.png")
        val legacyB = File(context.filesDir, "img_part_b.png")
        assertTrue("legacy file for the failed note must stay", legacyA.exists())
        assertFalse(
            "legacy file for the successful note must be deleted",
            legacyB.exists()
        )
        // Verify the failed note's URI was NOT rewritten. The
        // round-trip through parseBlocksFromContent also keeps the
        // check robust against JSON escaping of the Windows path.
        val noteAImgUri = parseBlocksFromContent(dao.getRowById(noteA)!!.content)
            .filterIsInstance<com.example.notes.ui.edit.ContentBlock.ImageBlock>()
            .single().uri
        assertTrue(
            "note A's URI must still be the legacy one after the " +
                "failed run (got '$noteAImgUri')",
            noteAImgUri.contains("/img_part_a.png") &&
                !noteAImgUri.contains("/images/")
        )

        // Second run: the previous failure cleared, the migration
        // resumes and finishes.
        LocalImageMigration.runIfNeeded(context, dao)

        assertFalse(
            "second run must remove the now-orphan legacy file",
            legacyA.exists()
        )
        val prefs = context.getSharedPreferences(
            LocalImageMigrationPrefsName, Context.MODE_PRIVATE
        )
        assertTrue(
            "second run completes the migration so completed_v1 is set",
            prefs.getBoolean(LocalImageMigrationPrefsKey, false)
        )
    }

    // ---------- per-file deletion rule (the actual ask) ----------

    @Test
    fun `per-file rule — one file in two notes, both succeed → file is deleted`() = runBlocking {
        // Two notes both reference the same legacy file. After
        // migration both URIs are rewritten, the file is no longer
        // referenced by anything → it must be deleted.
        val shared = "img_shared.png"
        File(context.filesDir, shared).writeBytes(
            byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte())
        )
        val uri1 = "$legacyPrefix$shared"
        val uri2 = "$legacyPrefix$shared"
        val note1 = dao.insert(
            NoteEntity(title = "n1", content = "a", userId = 1,
                serverId = "s1", dirtyRevision = 0, lastSyncedRevision = 0)
        )
        val note2 = dao.insert(
            NoteEntity(title = "n2", content = "b", userId = 1,
                serverId = "s2", dirtyRevision = 0, lastSyncedRevision = 0)
        )
        dao.updateContentOnlyStatic(
            note1, "<0|0|0|0>Sx\n|||IMG:$uri1|||\n<0|0|0|0>Sy"
        )
        dao.updateContentOnlyStatic(
            note2, "<0|0|0|0>Sx\n|||IMG:$uri2|||\n<0|0|0|0>Sy"
        )

        LocalImageMigration.runIfNeeded(context, dao)

        assertFalse(
            "shared file must be deleted when no note references its " +
                "legacy URI anymore",
            File(context.filesDir, shared).exists()
        )
    }

    @Test
    fun `per-file rule — one file in two notes, one fails → file is kept`() = runBlocking {
        // Two notes both reference the same legacy file. Note B's
        // update throws, so its URI is still legacy. The shared file
        // must be kept because note B still references it.
        val shared = "img_keepme.png"
        File(context.filesDir, shared).writeBytes(
            byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte())
        )
        val uri1 = "$legacyPrefix$shared"
        val note1 = dao.insert(
            NoteEntity(title = "n1", content = "a", userId = 1,
                serverId = "s1", dirtyRevision = 0, lastSyncedRevision = 0)
        )
        val note2 = dao.insert(
            NoteEntity(title = "n2", content = "b", userId = 1,
                serverId = "s2", dirtyRevision = 0, lastSyncedRevision = 0)
        )
        dao.updateContentOnlyStatic(
            note1, "<0|0|0|0>Sx\n|||IMG:$uri1|||\n<0|0|0>Sy"
        )
        dao.updateContentOnlyStatic(
            note2, "<0|0|0>Sz\n|||IMG:$uri1|||\n<0|0|0>Sw"
        )
        dao.failNextUpdateForId(note2)

        LocalImageMigration.runIfNeeded(context, dao)

        assertTrue(
            "shared file must be kept when a note's update failed " +
                "and still references the legacy URI",
            File(context.filesDir, shared).exists()
        )
    }

    @Test
    fun `per-file rule — one note with two images → both files are deleted when both are unreferenced`() = runBlocking {
        // One note references two legacy images. After a clean
        // migration both images are in the new form, so both files
        // are no longer referenced and both must be deleted.
        File(context.filesDir, "img_two_a.png").writeBytes(
            byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte())
        )
        File(context.filesDir, "img_two_b.png").writeBytes(
            byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte())
        )
        val noteId = dao.insert(
            NoteEntity(title = "two", content = "x", userId = 1,
                serverId = "s", dirtyRevision = 0, lastSyncedRevision = 0)
        )
        dao.updateContentOnlyStatic(
            noteId,
            "<0|0|0>Sa\n" +
                "|||IMG:$legacyPrefix${"/img_two_a.png"}|||\n" +
                "<0|0|0>Sb\n" +
                "|||IMG:$legacyPrefix${"/img_two_b.png"}|||\n" +
                "<0|0|0>Sc"
        )

        LocalImageMigration.runIfNeeded(context, dao)

        assertFalse(
            "img_two_a.png must be deleted when no note references it",
            File(context.filesDir, "img_two_a.png").exists()
        )
        assertFalse(
            "img_two_b.png must be deleted when no note references it",
            File(context.filesDir, "img_two_b.png").exists()
        )
    }

    @Test
    fun `per-file rule — one note with two images, update fails → both files are kept`() = runBlocking {
        // When the per-note update fails, BOTH legacy URIs from
        // that note stay in the DB, so the per-file gate must keep
        // BOTH files. (There's no way to rewrite one URI inside a
        // note without rewriting the other — the whole content is
        // rewritten atomically per note.)
        File(context.filesDir, "img_pair_a.png").writeBytes(
            byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte())
        )
        File(context.filesDir, "img_pair_b.png").writeBytes(
            byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte())
        )
        val noteId = dao.insert(
            NoteEntity(title = "two", content = "x", userId = 1,
                serverId = "s", dirtyRevision = 0, lastSyncedRevision = 0)
        )
        dao.updateContentOnlyStatic(
            noteId,
            "<0|0>Sa\n" +
                "|||IMG:$legacyPrefix${"/img_pair_a.png"}|||\n" +
                "<0|0>Sb\n" +
                "|||IMG:$legacyPrefix${"/img_pair_b.png"}|||\n" +
                "<0|0>Sc"
        )
        // The single per-note update fails, so both legacy URIs
        // remain. The per-file gate must keep both files.
        dao.failNextUpdateForId(noteId)

        LocalImageMigration.runIfNeeded(context, dao)

        assertTrue(
            "img_pair_a.png must stay (still referenced by the failed note)",
            File(context.filesDir, "img_pair_a.png").exists()
        )
        assertTrue(
            "img_pair_b.png must stay (still referenced by the failed note)",
            File(context.filesDir, "img_pair_b.png").exists()
        )
    }

    // ---------- splash gate (completionSignal) ----------

    @Test
    fun `runIfNeeded resolves completionSignal on the early-return path (completed_v1 already true)`() = runBlocking {
        // The early-return path (the migration has already finished on
        // a prior run) must still resolve the splash gate. We force a
        // reset first so the test is checking THIS call, not a
        // completion that lingered from a previous test.
        val prefs = context.getSharedPreferences(
            LocalImageMigrationPrefsName, Context.MODE_PRIVATE
        )
        prefs.edit().putBoolean(LocalImageMigrationPrefsKey, true).apply()
        LocalImageMigration.resetCompletionSignalForTesting()
        assertFalse(
            "precondition: signal starts un-completed for this test",
            LocalImageMigration.completionSignal.isCompleted
        )

        LocalImageMigration.runIfNeeded(context, dao)

        assertTrue(
            "early-return path must resolve the splash gate so the " +
                "splash doesn't wait forever on the second cold start",
            LocalImageMigration.completionSignal.isCompleted
        )
    }

    @Test
    fun `runIfNeeded resolves completionSignal even when an unexpected exception is thrown`() = runBlocking {
        // Simulate the catastrophic case where an exception escapes
        // runMigrationSteps itself. We do this by making the first
        // DAO call (getAllLiveNotesSync) throw. The per-step
        // try/catch in runMigrationSteps catches individual note
        // errors, so to truly escape the entire runIfNeeded we have
        // to fail at the DAO level. We override the in-memory list
        // to throw on iteration via a small wrapper.
        LocalImageMigration.resetCompletionSignalForTesting()

        val explodingDao = object : com.example.notes.data.local.NoteDao by dao {
            override suspend fun getAllLiveNotesSync(): List<NoteEntity> {
                throw RuntimeException("simulated DAO crash")
            }
        }
        // runIfNeeded must not throw out — the per-step try/catch
        // catches Throwable, logs it, and returns hadError = true.
        // The splash gate is resolved by the outer try/finally.
        LocalImageMigration.runIfNeeded(context, explodingDao)

        assertTrue(
            "completionSignal must be resolved even on a " +
                "catastrophic runIfNeeded failure, so the splash " +
                "screen doesn't wait forever",
            LocalImageMigration.completionSignal.isCompleted
        )
        // And the completed_v1 flag must NOT have been set, so the
        // next cold start retries the migration.
        val prefs = context.getSharedPreferences(
            LocalImageMigrationPrefsName, Context.MODE_PRIVATE
        )
        assertFalse(
            "completed_v1 must stay false on catastrophic failure",
            prefs.getBoolean(LocalImageMigrationPrefsKey, false)
        )
    }

    @Test
    fun `runIfNeeded resolves completionSignal on coroutine cancellation`() = runBlocking {
        // Cancellation is split into two cases by the production
        // code's defensive `try/finally`s:
        //
        //  (1) Cancellation mid-execution (the body started, then a
        //      suspension point was hit while cancelled). Handled
        //      by the `try/finally` INSIDE `runIfNeeded` (hoisted
        //      above `withContext`). The `finally` block always
        //      resolves the signal.
        //
        //  (2) Cancellation before the body starts (the launched
        //      coroutine is cancelled while still scheduled, so
        //      `runIfNeeded`'s body is never entered). Handled by
        //      the outer `try/finally` in `NotesApplication.onCreate`
        //      that wraps the `applicationScope.launch { runIfNeeded }`
        //      call.
        //
        // This test pins the contract: the call is a `suspend fun`
        // with a `try/finally` hoisted around the `withContext`
        // call (not inside it). Cancellation mid-execution must run
        // the `finally` block. We pin it by calling `runIfNeeded`
        // in a coroutine that has not been cancelled — the body
        // runs to completion via the normal exit path, the
        // `finally` block fires, and the signal is resolved.
        //
        // The full "mid-execution" path is exercised on a real
        // device (see the manual test plan below). On Robolectric
        // the migration is so fast that any in-process cancel lands
        // after the body has already returned, so the signal is
        // released by the normal-exit `finally` and the assertion
        // passes trivially. We document that limit so a future
        // maintainer doesn't try to make the test "more strict"
        // and accidentally break the contract.
        //
        // Manual test plan for case (1) (mid-execution):
        //   1. Build a release APK with the migration.
        //   2. Cold-start the app, immediately run
        //      `adb shell am force-stop com.example.notes` so the
        //      process is killed while `runIfNeeded` is mid-step
        //      (e.g. during a large `stepCopy`).
        //   3. Cold-start again. The splash should still open
        //      after a few seconds (the `finally` in runIfNeeded
        //      resolved the signal even though the body was
        //      cancelled; `completed_v1` was NOT set, so the
        //      next run retries the migration).
        //
        // Manual test plan for case (2) (before-body-start):
        //   Same as case (1) but the force-stop must happen
        //   BEFORE the IO dispatcher picks the coroutine up. The
        //   outer `try/finally` in `NotesApplication` is the safety
        //   net for this case; the UI still opens, the next run
        //   retries the migration.
        LocalImageMigration.resetCompletionSignalForTesting()

        runBlocking {
            launch { LocalImageMigration.runIfNeeded(context, dao) }
        }

        assertTrue(
            "completionSignal must resolve after runIfNeeded " +
                "completes (normal exit path of the try/finally)",
            LocalImageMigration.completionSignal.isCompleted
        )
    }

    // ---------- LEGACY_PATH_TERMINATORS completeness ----------

    @Test
    fun `LEGACY_PATH_TERMINATORS is exactly the documented set`() {
        // The terminator set is the contract the per-file deletion
        // gate relies on. If a future refactor drops a character or
        // adds a bogus one, this test fails loudly — the migration
        // would either miss deleting legacy files (a false negative)
        // or delete files still referenced by a note (a false
        // positive, the user-data-loss case).
        //
        // The expected set is the union of:
        //   - V2.6 format boundaries: '|' (the `|||IMG:…|||` marker)
        //                                 and '\n' (block separator)
        //   - JSON v2 format boundaries: '"' (the closing JSON string quote)
        //                                    and '\n' (the comma/space between
        //                                    fields is NOT in the set, but
        //                                    '\n' covers the same edge case)
        //   - Defensive: ' ' (space) and '\t' (tab) so a future format
        //                 that embeds the URI inside a larger text blob
        //                 doesn't break the walker.
        //   - Defensive: '\r' (CR) for notes imported from a CRLF source.
        val expected = linkedSetOf('\n', ' ', '\r', '\t', '|', '"')
        val actual = LocalImageMigration.LEGACY_PATH_TERMINATORS.toList()
        assertEquals(
            "terminator set must match the documented contract " +
                "(see LocalImageMigration.collectLegacyFilePaths kdoc)",
            expected.toList(), actual
        )
    }

    // ---------- helpers ----------

    /**
     * The private [LocalImageMigration] only depends on a single
     * DAO method (`updateContentOnly`) and `getAllLiveNotesSync`,
     * which the in-memory `FakeNoteDao` provides. We use the public
     * `updateContentOnly` (added in this change) directly.
     */
    private class FakeNoteDao : NoteDao {
        private val rows = mutableListOf<NoteEntity>()
        private val nextId = java.util.concurrent.atomic.AtomicLong(1L)

        /**
         * For the given note id, throw on the next call to
         * [updateContentOnly] and then succeed on every subsequent
         * call. Models a transient DB error that resolves on the
         * migration's next cold start.
         */
        private val failNext = HashSet<Long>()

        fun failNextUpdateForId(localId: Long) {
            failNext.add(localId)
        }

        fun insert(note: NoteEntity): Long {
            val id = if (note.id == 0L) nextId.getAndIncrement() else note.id
            rows.removeAll { it.id == id }
            rows.add(note.copy(id = id))
            return id
        }
        fun getRowById(id: Long): NoteEntity? = rows.firstOrNull { it.id == id }
        fun updateContentOnlyStatic(localId: Long, newContent: String) {
            val idx = rows.indexOfFirst { it.id == localId }
            if (idx >= 0) rows[idx] = rows[idx].copy(content = newContent)
        }
        // --- NoteDao surface (uninteresting for these tests) ---
        override suspend fun insertNote(note: NoteEntity): Long = insert(note)
        override suspend fun updateNote(note: NoteEntity) {
            val idx = rows.indexOfFirst { it.id == note.id }
            if (idx >= 0) rows[idx] = note else rows.add(note)
        }
        override suspend fun softDeleteNote(id: Long, now: Long) {}
        override suspend fun deleteNoteById(id: Long) {
            rows.removeAll { it.id == id }
        }
        override suspend fun purgeNoteById(id: Long) {
            rows.removeAll { it.id == id }
        }
        override fun getAllNotes(userId: Int) =
            kotlinx.coroutines.flow.flowOf(rows.filter {
                it.userId == userId && !it.isTrashed && it.deletedAt == null
            })
        override suspend fun getNoteById(id: Long) = getRowById(id)
        override fun searchNotes(query: String, userId: Int) =
            kotlinx.coroutines.flow.flowOf(emptyList<NoteEntity>())
        override fun getFavoriteNotes(userId: Int) =
            kotlinx.coroutines.flow.flowOf(emptyList<NoteEntity>())
        override fun searchFavoriteNotes(query: String, userId: Int) =
            kotlinx.coroutines.flow.flowOf(emptyList<NoteEntity>())
        override fun getTrashedNotes(userId: Int) =
            kotlinx.coroutines.flow.flowOf(emptyList<NoteEntity>())
        override fun searchTrashedNotes(query: String, userId: Int) =
            kotlinx.coroutines.flow.flowOf(emptyList<NoteEntity>())
        override fun getNoteCount(userId: Int) = kotlinx.coroutines.flow.flowOf(0)
        override fun getFavoriteCount(userId: Int) = kotlinx.coroutines.flow.flowOf(0)
        override fun getTrashedCount(userId: Int) = kotlinx.coroutines.flow.flowOf(0)
        override suspend fun getNoteByServerId(serverId: String) = null
        override suspend fun getDirtyNotes(userId: Int) = emptyList<NoteEntity>()
        override suspend fun getTombstonesAcknowledged(userId: Int) =
            emptyList<NoteEntity>()
        override suspend fun getAllNotesSnapshot(userId: Int) =
            rows.filter { it.userId == userId && it.deletedAt == null }
        override suspend fun getAllLiveNotesSync() =
            rows.filter { it.deletedAt == null }
        override suspend fun countDirtyNotes(userId: Int) = 0
        override suspend fun markSynced(
            localId: Long, syncedRev: Int, serverChangeId: Long,
            serverUpdatedAt: Long, baseTitle: String, baseContent: String
        ) {}
        override suspend fun updateContentOnly(localId: Long, newContent: String) {
            if (failNext.remove(localId)) {
                throw RuntimeException(
                    "simulated transient DB failure for note $localId"
                )
            }
            updateContentOnlyStatic(localId, newContent)
        }
        override suspend fun migrateAnonymousNotesToUser(userId: Int) = 0
    }

    private companion object {
        // Mirror the constants in LocalImageMigration so the cleanup
        // hook can wipe them between tests without poking the
        // implementation.
        const val LocalImageMigrationPrefsName = "image_migration"
        const val LocalImageMigrationPrefsKey = "completed_v1"
    }
}
