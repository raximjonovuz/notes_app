package com.example.notes.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * V5.0.2 regression test for the [ApiClient.upsertNote] envelope
 * mismatch that caused the "7 duplicates of one note" bug.
 *
 * The backend wraps the response as `{ "note": { ... } }` but the
 * client (until this fix) tried to deserialize the envelope as a
 * raw [NoteSyncDto], which threw `MissingFieldException` for every
 * field. The push then returned a failure result and the local
 * note's `serverId` stayed `null`, so the next push generated a fresh
 * UUID — and seven identical-looking notes accumulated on the
 * server. See `NotesSyncRepositoryTest` for the dedup logic that
 * keeps a single note from turning into seven in the first place.
 *
 * This test pins the wire format: a server that returns
 * `{ "note": { id, title, ... } }` is parsed correctly and the
 * caller receives the inner [NoteSyncDto].
 */
class ApiClientEnvelopeTest {

    private lateinit var server: MockWebServer
    private val originalBaseUrl = ApiClient.BASE_URL
    private val originalToken = ApiClient.authToken

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // Retarget the singleton at our local MockWebServer. The
        // production URL is unreachable from the JVM anyway, so this
        // keeps every other test from accidentally hitting it.
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
    fun `upsertNote parses the {note envelope} envelope and returns the inner NoteSyncDto`() = runBlocking {
        // The shape the backend actually returns.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "note": {
                        "id": "c0eb8128-718d-41ae-b2df-fea47d422f86",
                        "title": "test 3",
                        "content": "<1000S>18.40",
                        "is_favorite": 0,
                        "is_trashed": 0,
                        "background_color": 0
                      }
                    }
                    """.trimIndent()
                )
        )

        val request = NoteSyncDto(
            id = "c0eb8128-718d-41ae-b2df-fea47d422f86",
            title = "test 3",
            content = "<1000S>18.40",
            is_favorite = 0,
            is_trashed = 0,
            background_color = 0
        )

        val result = ApiClient.upsertNote(request)

        // The fix: the envelope is unwrapped, so the caller's
        // `Result<NoteSyncDto>` actually contains a NoteSyncDto
        // with the server-assigned id. If the regression returns,
        // the assertion below fails with the exact MissingFieldException
        // that the user observed in the wild.
        result.onFailure { ex ->
            fail("upsertNote should parse the envelope, but threw: ${ex.javaClass.simpleName}: ${ex.message}")
        }
        val dto: NoteSyncDto = result.getOrThrow()
        assertEquals("c0eb8128-718d-41ae-b2df-fea47d422f86", dto.id)
        assertEquals("test 3", dto.title)
        assertEquals("<1000S>18.40", dto.content)
    }

    @Test
    fun `upsertNote request body uses the client id (not a server-generated one)`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "note": {
                        "id": "fixed-uuid",
                        "title": "x",
                        "content": "",
                        "is_favorite": 0,
                        "is_trashed": 0,
                        "background_color": 0
                      }
                    }
                    """.trimIndent()
                )
        )

        val request = NoteSyncDto(
            id = "client-supplied-uuid",
            title = "x",
            content = "",
            is_favorite = 0,
            is_trashed = 0,
            background_color = 0
        )
        ApiClient.upsertNote(request)

        val recorded = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull("MockWebServer should have received the request", recorded)
        val body = recorded!!.body.readUtf8()
        // The body must carry the client-supplied id so the server
        // can upsert (and not create a fresh row when the local
        // note already had a serverId from a prior push).
        assertTrue(
            "request body must include the client id; got: $body",
            body.contains("\"id\":\"client-supplied-uuid\"")
        )
    }

    @Test
    fun `upsertNote returns failure for non-2xx responses`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("\"internal error\"")
        )

        val request = NoteSyncDto(
            id = "x", title = "x", content = "",
            is_favorite = 0, is_trashed = 0, background_color = 0
        )
        val result = ApiClient.upsertNote(request)
        assertTrue("expected failure for 500 response, got $result", result.isFailure)
    }

    @Test
    fun `upsertNote returns failure for empty body`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("")
        )

        val request = NoteSyncDto(
            id = "x", title = "x", content = "",
            is_favorite = 0, is_trashed = 0, background_color = 0
        )
        val result = ApiClient.upsertNote(request)
        assertTrue("expected failure for empty body, got $result", result.isFailure)
    }
}
