package com.example.notes.ui.util

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.example.notes.TestNotesApplication
import com.example.notes.data.remote.ApiClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = TestNotesApplication::class)
class LoadedImageTest {

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
        ApiClient.authToken = "test-token"
        imageBitmapCache.evictAll()
    }

    @After
    fun tearDown() {
        server.shutdown()
        ApiClient.BASE_URL = originalBaseUrl
        ApiClient.authToken = originalToken
        imageBitmapCache.evictAll()
    }

    private fun samplePngBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }

    @Test
    fun `loadImageBitmapFromUri successfully decodes HTTP 200 response and caches bitmap`() = kotlinx.coroutines.test.runTest {
        val bytes = samplePngBytes()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "image/png")
                .setBody(Buffer().write(bytes))
        )

        val imageUrl = server.url("/files/uploads/test.png").toString()

        // 1. First fetch — hits network and decodes correctly
        val bitmap = loadImageBitmapFromUri(context, imageUrl)
        assertNotNull("Bitmap must be successfully decoded from HTTP 200", bitmap)
        assertEquals(1, server.requestCount)

        // 2. Second fetch — served directly from in-memory cache without hitting network again
        val cached = loadImageBitmapFromUri(context, imageUrl)
        assertNotNull("Bitmap must be returned from cache", cached)
        assertEquals("Network request count must remain 1 due to cache hit", 1, server.requestCount)
    }

    @Test
    fun `loadImageBitmapFromUri returns null on HTTP 404`() = kotlinx.coroutines.test.runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val imageUrl = server.url("/files/uploads/missing.png").toString()
        val bitmap = loadImageBitmapFromUri(context, imageUrl)
        assertNull("Bitmap must be null on HTTP error", bitmap)
    }
}

