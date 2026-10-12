package com.example.notes.data.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.notes.util.ImageSniffer
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * End-to-end test for [ApiClient.uploadImage].
 *
 * The image-upload pipeline that [com.example.notes.ui.edit.NoteEditViewModel.addImageUri]
 * relies on has the following shape:
 *
 *   1. Editor picks a `content://` URI from the gallery and copies the bytes
 *      into [Context.getFilesDir].
 *   2. The local `file://` URI is then handed to
 *      [ApiClient.uploadImage] which:
 *        - reads the bytes via ContentResolver
 *        - sniffs the real image format with [ImageSniffer]
 *        - base64-encodes and POSTs `{filename, content_type, data}` to
 *          `/upload`
 *        - returns the JSON `{id, url, r2_key, content_type, size}` reply
 *   3. The ViewModel rewrites the image block URI with `response.url` so the
 *      note syncs to other devices.
 *
 * If step 2 mis-classifies the format (e.g. sends `content_type=image/jpeg`
 * and `filename=image.jpg` for actual PNG bytes), the round-trip bytes still
 * arrive at R2 untouched but the public URL is served with the wrong
 * Content-Type header, which breaks share targets / file inspectors that
 * sniff MIME and looks like "the image uploaded wrong" to the user.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = com.example.notes.TestNotesApplication::class)
class ApiClientUploadTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private val originalBaseUrl = ApiClient.BASE_URL
    private val originalToken = ApiClient.authToken

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer()
        server.start()
        ApiClient.BASE_URL = server.url("/").toString().trimEnd('/')
        ApiClient.authToken = "test-jwt"
    }

    @After
    fun tearDown() {
        server.shutdown()
        ApiClient.BASE_URL = originalBaseUrl
        ApiClient.authToken = originalToken
    }

    @Test
    fun `uploadImage sends base64 with the right MIME for a PNG file`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"id":"x","url":"https://e/files/uploads/1/x/image.png","r2_key":"k","content_type":"image/png","size":67}"""
                )
        )

        val png = writeFile(name = "sample.png", prefix = PNG_PREFIX)
        val res = ApiClient.uploadImage(android.net.Uri.fromFile(png), context)
        res.onFailure { fail("uploadImage failed: ${it.message}") }
        val payload = res.getOrThrow()
        assertEquals("https://e/files/uploads/1/x/image.png", payload.url)

        val recorded = takeRequest(server)
        val body = recorded.body.readUtf8()
        assertEquals("POST", recorded.method)
        assertEquals("/upload", recorded.path)
        assertTrue(
            "Authorization header is required for /upload",
            recorded.getHeader("Authorization") == "Bearer test-jwt"
        )
        assertTrue("body must contain base64 data: $body", body.contains("\"data\":\""))
        assertTrue(
            "PNG must be uploaded as image/png, not image/jpeg — the old version lied here for every non-JPEG file: $body",
            body.contains("\"content_type\":\"image/png\"")
        )
        assertTrue(
            "PNG must be uploaded as image.png, not image.jpg — old version used a hardcoded jpg ext: $body",
            body.contains("\"filename\":\"image.png\"")
        )
    }

    @Test
    fun `uploadImage uploads JPEGs as image jpeg with a jpg extension`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"id":"x","url":"https://e/files/uploads/1/x/image.jpg","r2_key":"k","content_type":"image/jpeg","size":3}"""
                )
        )

        val jpg = writeFile(name = "real.jpg", prefix = JPEG_PREFIX)
        ApiClient.uploadImage(android.net.Uri.fromFile(jpg), context)
            .onFailure { fail("uploadImage failed: ${it.message}") }

        val recorded = takeRequest(server)
        val body = recorded.body.readUtf8()
        assertTrue("JPEG should be content_type=image/jpeg: $body", body.contains("\"content_type\":\"image/jpeg\""))
        assertTrue("JPEG should be filename=image.jpg: $body", body.contains("\"filename\":\"image.jpg\""))
    }

    @Test
    fun `uploadImage uploads WebP as image webp with webp extension`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"id":"x","url":"https://e/files/uploads/1/x/image.webp","r2_key":"k","content_type":"image/webp","size":3}"""
                )
        )

        val webp = writeFile(name = "pic.webp", prefix = WEBP_PREFIX)
        ApiClient.uploadImage(android.net.Uri.fromFile(webp), context)
            .onFailure { fail("uploadImage failed: ${it.message}") }

        val recorded = takeRequest(server)
        val body = recorded.body.readUtf8()
        assertTrue("WebP should be image/webp: $body", body.contains("\"content_type\":\"image/webp\""))
        assertTrue("WebP should be image.webp: $body", body.contains("\"filename\":\"image.webp\""))
    }

    @Test
    fun `uploadImage uploads GIF as image gif with gif extension`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"id":"x","url":"https://e/files/uploads/1/x/image.gif","r2_key":"k","content_type":"image/gif","size":3}"""
                )
        )

        val gif = writeFile(name = "anim.gif", prefix = GIF_PREFIX)
        ApiClient.uploadImage(android.net.Uri.fromFile(gif), context)
            .onFailure { fail("uploadImage failed: ${it.message}") }

        val recorded = takeRequest(server)
        val body = recorded.body.readUtf8()
        assertTrue("GIF should be image/gif: $body", body.contains("\"content_type\":\"image/gif\""))
        assertTrue("GIF should be image.gif: $body", body.contains("\"filename\":\"image.gif\""))
    }

    @Test
    fun `uploadImage uploads HEIC as image heic with heic extension`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"id":"x","url":"https://e/files/uploads/1/x/image.heic","r2_key":"k","content_type":"image/heic","size":3}"""
                )
        )

        val heic = writeFile(name = "photo.heic", prefix = HEIC_PREFIX)
        ApiClient.uploadImage(android.net.Uri.fromFile(heic), context)
            .onFailure { fail("uploadImage failed: ${it.message}") }

        val recorded = takeRequest(server)
        val body = recorded.body.readUtf8()
        assertTrue("HEIC should be image/heic: $body", body.contains("\"content_type\":\"image/heic\""))
        assertTrue("HEIC should be image.heic: $body", body.contains("\"filename\":\"image.heic\""))
    }

    @Test
    fun `uploadImage returns failure with a human-readable message on 4xx`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"Only image/* content types are allowed"}""")
        )
        val png = writeFile(name = "bad.png", prefix = PNG_PREFIX)
        val result = ApiClient.uploadImage(android.net.Uri.fromFile(png), context)
        assertTrue("4xx should be a failure", result.isFailure)
        assertNotNull("exception must not be null for failures", result.exceptionOrNull())
        assertTrue(
            "error message should mention HTTP 400 so the user can diagnose",
            (result.exceptionOrNull()!!.message ?: "").contains("HTTP 400")
        )
    }

    @Test
    fun `ImageSniffer classifies common image bytes correctly`() {
        // Round-trip every signature through the sniffer itself so the
        // helper can't drift away from what the upload test asserts.
        assertEquals("image/png", ImageSniffer.detect(PNG_PREFIX)!!.mime)
        assertEquals("image/jpeg", ImageSniffer.detect(JPEG_PREFIX)!!.mime)
        assertEquals("image/gif", ImageSniffer.detect(GIF_PREFIX)!!.mime)
        assertEquals("image/webp", ImageSniffer.detect(WEBP_PREFIX)!!.mime)
        assertEquals("image/heic", ImageSniffer.detect(HEIC_PREFIX)!!.mime)
        // Bogus bytes → null so callers can fall back.
        org.junit.Assert.assertNull(ImageSniffer.detect(byteArrayOf(0x00, 0x01, 0x02, 0x03)))
        org.junit.Assert.assertNull(ImageSniffer.detect(ByteArray(0)))
    }

    // ---------- helpers + magic-byte constants ----------

    private fun takeRequest(server: MockWebServer): RecordedRequest {
        // Match ApiClientEnvelopeTest's successful usage: takeRequest returns
        // RecordedRequest? from Kotlin's POV (Java platform type). We assign
        // first, then null-check; assertNotNull smart-casts accordingly.
        val recorded = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull("MockWebServer did not receive a request within 2s", recorded)
        return recorded!!
    }

    private fun writeFile(name: String, prefix: ByteArray, extraBytes: Int = 4): File {
        val dir = File(context.filesDir, "upload-test").apply { mkdirs() }
        val file = File(dir, name)
        val bytes = ByteArray(prefix.size + extraBytes)
        System.arraycopy(prefix, 0, bytes, 0, prefix.size)
        file.writeBytes(bytes)
        return file
    }

    // PNG: 89 50 4E 47 0D 0A 1A 0A
    private val PNG_PREFIX = byteArrayOf(
        0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
        0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte()
    )
    // JPEG: FF D8 FF E0
    private val JPEG_PREFIX = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()
    )
    // GIF: "GIF89a"
    private val GIF_PREFIX = "GIF89a".toByteArray(Charsets.US_ASCII)
    // WebP: "RIFF" + size + "WEBP"
    private val WEBP_PREFIX = "RIFF".toByteArray(Charsets.US_ASCII) +
        byteArrayOf(0x24, 0x00, 0x00, 0x00) +
        "WEBP".toByteArray(Charsets.US_ASCII)
    // HEIC ISO-BMFF: bytes [0..3] ignored, [4..7] "ftyp", [8..11] brand "heic"
    private val HEIC_PREFIX = byteArrayOf(
        0x00, 0x00, 0x00, 0x18,
        'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
        'h'.code.toByte(), 'e'.code.toByte(), 'i'.code.toByte(), 'c'.code.toByte()
    )
}
