package com.example.notes.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.notes.ui.edit.ContentBlock
import com.example.notes.ui.edit.parseBlocksFromContent
import com.example.notes.ui.edit.serializeBlocksToContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F-05 — unit tests for the wire sanitizer. These are the fast,
 * network-free guards for the "the server must never see this
 * device's filesystem path" invariant.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = com.example.notes.TestNotesApplication::class)
class LocalImageUriTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun contentWith(vararg uris: String, text: String = "hello"): String {
        val blocks = mutableListOf<ContentBlock>(ContentBlock.TextBlock(text = text))
        uris.forEach { blocks += ContentBlock.ImageBlock(uri = it) }
        return serializeBlocksToContent(blocks)
    }

    @Test
    fun `isLocalImageUri recognises the new images subdir prefix`() {
        val uri = "file://${context.filesDir.absolutePath}/images/img_a.png"
        assertTrue(
            "the editor's new image path must be recognised as local",
            LocalImageUri.isLocalImageUri(uri, context)
        )
    }

    @Test
    fun `isLocalImageUri recognises the legacy filesDir root prefix`() {
        val uri = "file://${context.filesDir.absolutePath}/img_a.png"
        assertTrue(
            "the pre-F-28 image path must be recognised as local too",
            LocalImageUri.isLocalImageUri(uri, context)
        )
    }

    @Test
    fun `isLocalImageUri does not match a public R2 URL`() {
        val uri = "https://notes-backend.raximjonozod1727.workers.dev" +
            "/files/uploads/3/abc/img_a.png"
        assertFalse(
            "a public R2 URL is not a local file",
            LocalImageUri.isLocalImageUri(uri, context)
        )
    }

    @Test
    fun `isLocalImageUri does not match a foreign file URL`() {
        val uri = "file:///data/user/0/com.example.otherapp/files/img_a.png"
        assertFalse(
            "another app's file is not ours",
            LocalImageUri.isLocalImageUri(uri, context)
        )
    }

    @Test
    fun `sanitizeForWire replaces a local image URI with the pending-image marker`() {
        val localUri = "file://${context.filesDir.absolutePath}/images/img_a.png"
        val wire = LocalImageUri.sanitizeForWire(contentWith(localUri), context)

        assertFalse(
            "the local file:// URI must not survive (wire=$wire)",
            wire.contains("file://")
        )
        assertFalse(
            "the device's filesystem path must not survive (wire=$wire)",
            wire.contains(context.filesDir.absolutePath)
        )
        assertTrue(
            "the pending-image marker must be present (wire=$wire)",
            wire.contains(LocalImageUri.MARKER_PREFIX)
        )
        // The marker keeps the random file name (img_<UUID>.<ext>) so
        // the images of a note stay distinguishable — but the file
        // name carries no device information.
        assertTrue(
            "the marker must keep the file's random name (wire=$wire)",
            wire.contains("img_a.png")
        )
    }

    @Test
    fun `sanitizeForWire leaves a public URL untouched`() {
        val publicUrl = "https://notes-backend.raximjonozod1727.workers.dev" +
            "/files/uploads/3/abc/img_a.png"
        val original = contentWith(publicUrl)
        val wire = LocalImageUri.sanitizeForWire(original, context)
        assertEquals(
            "a note with only public images must be sent verbatim",
            original, wire
        )
    }

    @Test
    fun `sanitizeForWire is idempotent`() {
        val localUri = "file://${context.filesDir.absolutePath}/images/img_a.png"
        val once = LocalImageUri.sanitizeForWire(contentWith(localUri), context)
        val twice = LocalImageUri.sanitizeForWire(once, context)
        assertEquals(
            "sanitizing an already-sanitized content must be a no-op",
            once, twice
        )
    }

    @Test
    fun `sanitizeForWire keeps the JSON v2 structure intact`() {
        val localUri = "file://${context.filesDir.absolutePath}/images/img_a.png"
        val wire = LocalImageUri.sanitizeForWire(contentWith(localUri), context)
        // The result must still be a valid v2 envelope with the same
        // number of blocks.
        val blocks = parseBlocksFromContent(wire)
        assertEquals(
            "the block count must not change",
            2, blocks.size
        )
        val imgs = blocks.filterIsInstance<ContentBlock.ImageBlock>()
        assertEquals("the image block must survive", 1, imgs.size)
        assertTrue(
            "the image block must now carry the marker",
            LocalImageUri.isMarker(imgs[0].uri)
        )
        assertEquals(
            "the wire content must still be a v2 envelope",
            true, wire.trimStart().startsWith("{\"v\":2")
        )
    }

    @Test
    fun `sanitizeForWire replaces several local images independently`() {
        val a = "file://${context.filesDir.absolutePath}/images/img_a.png"
        val b = "file://${context.filesDir.absolutePath}/images/img_b.png"
        val publicUrl = "https://example.com/img_c.png"
        val wire = LocalImageUri.sanitizeForWire(contentWith(a, b, publicUrl), context)

        val imgs = parseBlocksFromContent(wire)
            .filterIsInstance<ContentBlock.ImageBlock>()
        assertEquals("all three image blocks survive", 3, imgs.size)
        assertTrue("the first local image is marked", LocalImageUri.isMarker(imgs[0].uri))
        assertTrue("the second local image is marked", LocalImageUri.isMarker(imgs[1].uri))
        assertEquals("the public URL is untouched", publicUrl, imgs[2].uri)
    }

    @Test
    fun `sanitizeForWire leaves a note without images completely alone`() {
        val original = serializeBlocksToContent(
            listOf(ContentBlock.TextBlock(text = "just text"))
        )
        assertEquals(
            "a text-only note must be sent verbatim",
            original, LocalImageUri.sanitizeForWire(original, context)
        )
    }
}
