package com.example.notes.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import com.example.notes.data.local.AuthStore
import com.example.notes.data.local.NoteDao
import com.example.notes.data.local.NoteEntity
import com.example.notes.data.local.SyncStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.3.x end-to-end tests for [NotesSyncRepository] push + pull
 * behaviour against a [MockWebServer]. The existing
 * [NotesSyncRepositoryTest] exercises the in-memory DAO side; this
 * suite covers the parts where the repository talks to the actual
 * [ApiClient] — tombstone purge (B1), per-user cursor namespacing
 * (B6), and the locally-generated UUID carried by every push (B3).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = com.example.notes.TestNotesApplication::class)
class NotesSyncRepositorySyncTest {

    private lateinit var context: android.content.Context
    private lateinit var dao: FakeNoteDao
    private lateinit var testDispatcher: TestDispatcher
    private lateinit var testScope: TestScope
    private lateinit var repo: NotesSyncRepository
    private lateinit var server: MockWebServer
    private val originalToken = ApiClient.authToken
    private val originalBaseUrl = ApiClient.BASE_URL

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer()
        server.start()
        ApiClient.BASE_URL = server.url("/").toString().trimEnd('/')
        ApiClient.authToken = "test-jwt"
        // Robolectric doesn't pre-populate the ConnectivityManager with
        // an active network, so [NotesSyncRepository.isOnline] would
        // always return false and the push/pull pass would short-circuit.
        // Promote a fake network with INTERNET so the repo can actually
        // talk to MockWebServer.
        setNetworkOnline(true)
        dao = FakeNoteDao()
        testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)
        repo = NotesSyncRepository(
            noteDao = dao,
            context = context,
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            pushScope = testScope
        )
        // Sign in as user 3 for the B1/B3 tests; the B6 test signs in
        // as a second user on top of that.
        AuthStore.saveOrUpdate(
            context,
            AuthStore.Session(
                email = "user3@example.com",
                userId = 3,
                token = "test-jwt"
            )
        )
    }

    @After
    fun tearDown() {
        // F-05 — cancel any debounced push this test scheduled so it
        // can't fire against a LATER test's MockWebServer.
        testScope.cancel()
        AuthStore.clearAll(context)
        SyncStore.setLastCursor(context, userId = 3, cursor = 0L)
        SyncStore.setLastCursor(context, userId = 7, cursor = 0L)
        setNetworkOnline(false)
        server.shutdown()
        ApiClient.authToken = originalToken
        ApiClient.BASE_URL = originalBaseUrl
    }

    /**
     * B1 — a soft-deleted row (serverId known, deletedAt != null) must
     * be pushed as a DELETE; the server returns 200 → the local row is
     * purged in the same pass. The pre-B1 behaviour was to leave the
     * row around until a confirming pull, which never happened for
     * already-synced tombstones.
     */
    @Test
    fun `B1 synced tombstone is purged locally when server returns 200 to DELETE`() = runBlocking {
        val serverUuid = "deadbeef-0000-0000-0000-000000000001"
        dao.insert(
            NoteEntity(
                id = 1,
                title = "to-be-deleted",
                content = "x",
                userId = 3,
                serverId = serverUuid,
                dirtyRevision = 2,
                lastSyncedRevision = 1,
                serverChangeId = 99,
                deletedAt = 1_700_000_000_000L
            )
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"deleted":"$serverUuid","already":false}""")
        )

        val pushed = repo.pushLocalChanges()
        assertEquals("one tombstone must be pushed", 1, pushed)

        val recorded = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull("MockWebServer should have received the DELETE", recorded)
        assertEquals("DELETE", recorded!!.method)
        assertEquals("/notes/$serverUuid", recorded.path)

        val after = dao.allRows()
        assertTrue("local row must be gone after a successful DELETE", after.none { it.id == 1L })
    }

    /**
     * B1 — a soft-deleted row that the server no longer has (404) must
     * still purge the local copy; the contract is that 200 and 404 are
     * both terminal. This is the path that cleans up tombstones whose
     * server-side rows have already been hard-deleted by retention.
     */
    @Test
    fun `B1 synced tombstone is purged locally when server returns 404 to DELETE`() = runBlocking {
        val serverUuid = "deadbeef-0000-0000-0000-000000000002"
        dao.insert(
            NoteEntity(
                id = 2,
                title = "already-gone",
                content = "x",
                userId = 3,
                serverId = serverUuid,
                dirtyRevision = 2,
                lastSyncedRevision = 1,
                deletedAt = 1_700_000_000_000L
            )
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"Note not found"}""")
        )

        val pushed = repo.pushLocalChanges()
        assertEquals("tombstone must be considered pushed even on 404", 1, pushed)
        assertTrue(
            "local row must be gone after a 404 to DELETE",
            dao.allRows().none { it.id == 2L }
        )
    }

    /**
     * B1 — a 5xx from the server must NOT purge the local row; the next
     * push retry should re-issue the DELETE. We pin this so a future
     * "treat any 4xx as success" optimisation can't accidentally drop
     * live data on a transient error.
     */
    @Test
    fun `B1 synced tombstone stays dirty when server returns 500`() = runBlocking {
        val serverUuid = "deadbeef-0000-0000-0000-000000000003"
        dao.insert(
            NoteEntity(
                id = 3,
                title = "retry-me",
                content = "x",
                userId = 3,
                serverId = serverUuid,
                dirtyRevision = 2,
                lastSyncedRevision = 1,
                deletedAt = 1_700_000_000_000L
            )
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setBody("""{"error":"unavailable"}""")
        )

        val pushed = repo.pushLocalChanges()
        assertEquals("a 503 must not be counted as pushed", 0, pushed)

        val after = dao.allRows()
        assertTrue("local tombstone must survive a transient 503", after.any { it.id == 3L })
        // dirtyRevision is still greater than lastSyncedRevision so the
        // next push will retry.
        val row = after.first { it.id == 3L }
        assertTrue(
            "dirtyRevision must remain > lastSyncedRevision for retry",
            row.dirtyRevision > row.lastSyncedRevision
        )
    }

    /**
     * B1 — pull side: when the server returns a tombstone
     * (`deleted_at != null`) for a serverId we hold locally, the row
     * is purged immediately. No "wait for the next push to confirm"
     * dance.
     */
    @Test
    fun `B1 pull purges local row when server returns a tombstone`() = runBlocking {
        val serverUuid = "deadbeef-0000-0000-0000-000000000004"
        dao.insert(
            NoteEntity(
                id = 4,
                title = "remote-killed",
                content = "x",
                userId = 3,
                serverId = serverUuid,
                dirtyRevision = 1,
                lastSyncedRevision = 1,
                serverChangeId = 50
            )
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "notes": [
                        {
                          "id": "$serverUuid",
                          "title": "",
                          "content": "",
                          "is_favorite": 0,
                          "is_trashed": 0,
                          "background_color": 0,
                          "client_updated_at": 1700000000000,
                          "created_at": 1700000000000,
                          "change_id": 51,
                          "deleted_at": 1700000001000
                        }
                      ],
                      "sync_timestamp": 1700000002000,
                      "sync_cursor": 51,
                      "has_more": false
                    }
                    """.trimIndent()
                )
        )

        repo.syncIfOnline()

        val recorded = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull("pull should have hit the server", recorded)
        assertEquals("GET", recorded!!.method)

        assertTrue(
            "local row must be purged on a tombstone pull",
            dao.allRows().none { it.id == 4L }
        )
    }

    /**
     * B3 — the push body must carry the local UUID that was stamped at
     * insert time. The server's UPSERT is a no-op when the same id
     * comes in twice, so two parallel pushes of the same note end up
     * as one server row. We pin that the request body's `id` matches
     * the local row's `serverId`.
     */
    @Test
    fun `B3 push body carries the locally generated UUID`() = runBlocking {
        val localUuid = UUID.randomUUID().toString()
        dao.insert(
            NoteEntity(
                id = 5,
                title = "uuid-pinned",
                content = "x",
                userId = 3,
                serverId = localUuid,
                dirtyRevision = 1,
                lastSyncedRevision = 0
            )
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "note": {
                        "id": "$localUuid",
                        "title": "uuid-pinned",
                        "content": "x",
                        "is_favorite": 0,
                        "is_trashed": 0,
                        "background_color": 0,
                        "client_updated_at": 1700000000000,
                        "created_at": 1700000000000,
                        "change_id": 7
                      }
                    }
                    """.trimIndent()
                )
        )

        val pushed = repo.pushLocalChanges()
        assertEquals(1, pushed)

        val recorded = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull("push should have hit the server", recorded)
        val body = recorded!!.body.readUtf8()
        assertTrue(
            "push body must carry the local UUID as id (B3 fix): got $body",
            body.contains("\"id\":\"$localUuid\"")
        )
        // markSynced should have flipped lastSyncedRevision == dirtyRevision
        val row = dao.allRows().first { it.id == 5L }
        assertEquals(
            "after a successful push the row is no longer dirty",
            row.dirtyRevision,
            row.lastSyncedRevision
        )
    }

    /**
     * B3 — the local UUID must NOT change between two pushes. A second
     * push of the same note sends the same UUID the server already
     * echoes; no replacement / regeneration.
     */
    @Test
    fun `B3 second push for the same row uses the same UUID`() = runBlocking {
        val localUuid = UUID.randomUUID().toString()
        dao.insert(
            NoteEntity(
                id = 6,
                title = "stable-uuid",
                content = "first",
                userId = 3,
                serverId = localUuid,
                dirtyRevision = 1,
                lastSyncedRevision = 0
            )
        )
        // Two identical UPSERT responses so we can push twice.
        repeat(2) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "note": {
                            "id": "$localUuid",
                            "title": "stable-uuid",
                            "content": "first",
                            "is_favorite": 0,
                            "is_trashed": 0,
                            "background_color": 0,
                            "client_updated_at": 1700000000000,
                            "created_at": 1700000000000,
                            "change_id": ${10 + it}
                          }
                        }
                        """.trimIndent()
                    )
            )
        }

        repo.pushLocalChanges()
        // Mutate locally so the row is dirty again.
        dao.update(dao.getRowById(6L)!!.copy(content = "second", dirtyRevision = 2))
        repo.pushLocalChanges()

        val first = server.takeRequest(2, TimeUnit.SECONDS)
        val second = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull("first push must have hit the server", first)
        assertNotNull("second push must have hit the server", second)
        assertEquals(
            "the first push's id matches the local serverId",
            true,
            first!!.body.readUtf8().contains("\"id\":\"$localUuid\"")
        )
        assertEquals(
            "the second push's id matches the same local serverId (B3)",
            true,
            second!!.body.readUtf8().contains("\"id\":\"$localUuid\"")
        )
        // serverId never changes on the local row.
        assertEquals(localUuid, dao.getRowById(6L)!!.serverId)
    }

    /**
     * B6 — sync state is namespaced per user. A pull that updates the
     * cursor for user 7 must NOT move user 3's cursor. Without the
     * per-user namespace a switch-account would replay the wrong
     * delta set.
     */
    @Test
    fun `B6 per-user cursors do not leak across accounts`() = runBlocking {
        // Seed user 3 with a known cursor so any cross-account overwrite
        // is detectable.
        SyncStore.setLastCursor(context, userId = 3, cursor = 100L)
        // Reset user 7 — no cursor yet.
        SyncStore.setLastCursor(context, userId = 7, cursor = 0L)

        // Switch active session to user 7.
        AuthStore.saveOrUpdate(
            context,
            AuthStore.Session(
                email = "user7@example.com",
                userId = 7,
                token = "test-jwt"
            )
        )

        // Empty pull for user 7 → cursor advances to 200 in user 7's
        // slot, user 3's slot must NOT change.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "notes": [],
                      "sync_timestamp": 1700000002000,
                      "sync_cursor": 200,
                      "has_more": false
                    }
                    """.trimIndent()
                )
        )
        repo.syncIfOnline()
        server.takeRequest(2, TimeUnit.SECONDS)

        assertEquals(
            "user 7's cursor must advance to the value the pull returned",
            200L,
            SyncStore.getLastCursor(context, userId = 7)
        )
        assertEquals(
            "user 3's cursor must be untouched by user 7's pull (B6)",
            100L,
            SyncStore.getLastCursor(context, userId = 3)
        )
    }

    /** Helper: serverId of a row that has been inserted into [dao]. */
    private fun serverIdOf(localId: Long): String =
        dao.getRowById(localId)?.serverId ?: error("no row $localId")

    /**
     * Set the Robolectric ConnectivityManager to a known state. When
     * [online] is true we register a network with the INTERNET
     * capability so [NotesSyncRepository.isOnline] returns true and
     * the push / pull passes actually run.
     */
    private fun setNetworkOnline(online: Boolean) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadow = shadowOf(cm)
        if (!online) {
            shadow.clearAllNetworks()
            shadow.setDefaultNetworkActive(false)
            return
        }
        val network = ShadowNetwork.newInstance(0)
        shadow.setDefaultNetworkActive(true)
        // NetworkCapabilities.Builder is hidden behind @SystemApi / @hide
        // in some SDK levels (the public `Builder` class is missing from
        // the SDK stub that @Config(sdk=[30]) pulls in), so we build
        // the instance reflectively. The only check it has to satisfy
        // is `hasCapability(NET_CAPABILITY_INTERNET)` — that's what the
        // repo reads.
        val caps = newNetworkCapabilitiesWithInternet()
        shadow.setNetworkCapabilities(network, caps)
    }

    /**
     * Build a [NetworkCapabilities] with `NET_CAPABILITY_INTERNET` set,
     * using plain Java reflection so we don't depend on the inner
     * `Builder` class being visible on the test classpath.
     */
    private fun newNetworkCapabilitiesWithInternet(): NetworkCapabilities {
        val builderClass = Class.forName("android.net.NetworkCapabilities\$Builder")
        val builder = builderClass.getDeclaredConstructor().newInstance()
        val addCapability = builderClass.getMethod(
            "addCapability",
            Int::class.javaPrimitiveType
        )
        addCapability.invoke(builder, NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val build = builderClass.getMethod("build")
        return build.invoke(builder) as NetworkCapabilities
    }

    /**
     * F-05 — the push payload must never carry a local `file://` URI
     * (it would leak this device's filesystem path to the server and
     * to every other device). The local image block is replaced by a
     * synthetic `pending-image:` marker instead.
     *
     * The LOCAL row keeps its `file://` URI — only the wire copy is
     * sanitized.
     */
    @Test
    fun `F-05 push never sends a local file URI to the server`() = runBlocking {
        val localUri = "file://${context.filesDir.absolutePath}/images/img_secret.png"
        val localId = dao.insert(
            NoteEntity(
                id = 90,
                title = "with image",
                content = noteContentWithImage(localUri, "hello"),
                userId = 3,
                serverId = "uuid-secret",
                dirtyRevision = 2,
                lastSyncedRevision = 1
            )
        )
        // Burn the retry budget so the push is no longer deferred.
        burnRetryBudget(90L)
        server.enqueue(upsertOk("uuid-secret", 42))

        repo.pushLocalChanges()

        val recorded = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull("push must have reached the server", recorded)
        val body = recorded!!.body.readUtf8()

        assertFalse(
            "the local file:// URI must NOT be sent to the server " +
                "(body=$body)",
            body.contains("file://")
        )
        assertFalse(
            "the device's filesystem path must not leak to the " +
                "server (body=$body)",
            body.contains(context.filesDir.absolutePath)
        )
        assertTrue(
            "the image block must be replaced by the pending-image " +
                "marker (body=$body)",
            body.contains("pending-image:")
        )
        // The LOCAL row keeps its `file://` URI so the editor can
        // still render the image and re-upload it.
        val localRow = dao.getRowById(localId)!!
        assertTrue(
            "the local row must keep its file:// URI",
            localRow.content.contains("file://")
        )
        assertTrue(
            "the local row must keep the original file name",
            localRow.content.contains("img_secret.png")
        )
    }

    /**
     * F-05 — once the image has been uploaded, the editor replaces
     * the local URI with the public R2 URL. The next push must send
     * that real URL (and NOT a marker).
     */
    @Test
    fun `F-05 push sends the public R2 URL once the image has uploaded`() = runBlocking {
        val publicUrl = "https://notes-backend.raximjonozod1727.workers.dev" +
            "/files/uploads/3/abc/img_x.png"
        dao.insert(
            NoteEntity(
                id = 91,
                title = "uploaded image",
                content = noteContentWithImage(publicUrl, "hello"),
                userId = 3,
                serverId = "uuid-public",
                dirtyRevision = 2,
                lastSyncedRevision = 1
            )
        )
        server.enqueue(upsertOk("uuid-public", 43))

        repo.pushLocalChanges()

        val recorded = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull("push must have reached the server", recorded)
        val body = recorded!!.body.readUtf8()

        assertTrue(
            "the public R2 URL must be sent verbatim (body=$body)",
            body.contains("img_x.png")
        )
        assertFalse(
            "no pending-image marker may be sent for an uploaded " +
                "image (body=$body)",
            body.contains("pending-image:")
        )
    }

    /**
     * F-05 — the core of the fix: after the image's retry budget is
     * exhausted, the note's TEXT must still reach the server (with
     * the image replaced by the marker). Before the fix the whole
     * note was deferred forever and the text never synced.
     */
    @Test
    fun `F-05 after the retry cap the note text is still pushed`() = runBlocking {
        val localUri = "file://${context.filesDir.absolutePath}/images/img_giveup.png"
        dao.insert(
            NoteEntity(
                id = 92,
                title = "text must sync",
                content = noteContentWithImage(localUri, "IMPORTANT TEXT"),
                userId = 3,
                serverId = "uuid-giveup",
                dirtyRevision = 2,
                lastSyncedRevision = 1
            )
        )
        // Push the note into the "gave up" state by burning the
        // whole retry budget.
        burnRetryBudget(92L)
        assertTrue(
            "the note must be in the gave-up state",
            com.example.notes.util.ImageRetryStore
                .noteGaveUpImage(context, 92L)
        )

        server.enqueue(upsertOk("uuid-giveup", 44))

        val pushed = repo.pushLocalChanges()

        assertEquals("the note must have been pushed", 1, pushed)
        val recorded = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull("push must have reached the server", recorded)
        val body = recorded!!.body.readUtf8()
        assertTrue(
            "the note's TEXT must reach the server even though the " +
                "image failed (body=$body)",
            body.contains("IMPORTANT TEXT")
        )
        assertFalse(
            "the local file:// URI must still not be sent (body=$body)",
            body.contains("file://")
        )
    }

    /**
     * F-05 — while the image upload is still in flight (retry
     * budget NOT exhausted), the push must stay deferred: we must
     * not publish a `pending-image:` marker for an image that is
     * about to succeed. The marker only appears after the give-up.
     */
    @Test
    fun `F-05 push stays deferred while the image upload is still in flight`() = runBlocking {
        val localUri = "file://${context.filesDir.absolutePath}/images/img_inflight.png"
        dao.insert(
            NoteEntity(
                id = 93,
                title = "in flight",
                content = noteContentWithImage(localUri, "waiting"),
                userId = 3,
                serverId = "uuid-inflight",
                dirtyRevision = 2,
                lastSyncedRevision = 1
            )
        )
        com.example.notes.util.ImageRetryStore.reset(context, 93L)

        // Drain anything a PREVIOUS test left on the server. A
        // single short poll — NOT a loop: the deferral path
        // schedules a 5-second retry, and a looping drain would
        // keep consuming those retries forever. The tear-down
        // cancels our own pending retry so it can't leak into the
        // next test either.
        server.takeRequest(50, TimeUnit.MILLISECONDS)

        val pushed = repo.pushLocalChanges()

        assertEquals("an in-flight image must still defer the push", 0, pushed)
        // Nothing may be sent for OUR note as a result of THIS
        // call. A leftover request from an earlier test is
        // tolerated as long as it isn't about this note.
        val recorded = server.takeRequest(200, TimeUnit.MILLISECONDS)
        if (recorded != null) {
            val leakedBody = recorded.body.readUtf8()
            assertFalse(
                "no request for THIS note may be sent while the image " +
                    "is still in flight (leaked body=$leakedBody)",
                leakedBody.contains("uuid-inflight")
            )
        }
    }

    /**
     * F-05 — after a note with a local `file://` image is pushed with
     * a `pending-image:<id>` marker (dirtyRevision == lastSyncedRevision),
     * a subsequent pull that returns that note from the server (with the
     * marker and updated server text) must preserve the local `file://`
     * URI on the image block while taking the updated text from the server,
     * and [com.example.notes.util.LocalImageCleanup] must NOT delete the
     * referenced local file.
     */
    @Test
    fun `F-05 pull preserves local file URI when server returns pending-image marker`() = runBlocking {
        val imagesDir = java.io.File(context.filesDir, "images").apply { mkdirs() }
        val localFile = java.io.File(imagesDir, "img_keep_after_pull.png")
        localFile.writeBytes(byteArrayOf(1, 2, 3, 4))
        try {
            val localUri = "file://${context.filesDir.absolutePath}/images/img_keep_after_pull.png"
            val localId = dao.insert(
                NoteEntity(
                    id = 94,
                    title = "local title",
                    content = noteContentWithImage(localUri, "local text"),
                    userId = 3,
                    serverId = "uuid-keep-local",
                    dirtyRevision = 2,
                    lastSyncedRevision = 1
                )
            )
            burnRetryBudget(94L)
            server.enqueue(upsertOk("uuid-keep-local", 50))

            val pushed = repo.pushLocalChanges()
            assertEquals("note must be pushed", 1, pushed)
            server.takeRequest(2, TimeUnit.SECONDS)

            val afterPush = dao.getRowById(localId)!!
            assertEquals(
                "precondition: dirtyRevision == lastSyncedRevision after push",
                afterPush.dirtyRevision,
                afterPush.lastSyncedRevision
            )

            // Server returns the note on pull with the pending-image marker
            // and updated text from the server.
            val serverContentWithMarker = noteContentWithImage(
                "pending-image:img_keep_after_pull.png",
                "server updated text"
            )
            val escapedServerContent = kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.serializer<String>(),
                serverContentWithMarker
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "notes": [
                            {
                              "id": "uuid-keep-local",
                              "title": "server title",
                              "content": $escapedServerContent,
                              "is_favorite": 0,
                              "is_trashed": 0,
                              "background_color": 0,
                              "client_updated_at": 1700000005000,
                              "created_at": 1700000000000,
                              "change_id": 51
                            }
                          ],
                          "sync_timestamp": 1700000006000,
                          "sync_cursor": 51,
                          "has_more": false
                        }
                        """.trimIndent()
                    )
            )

            repo.syncIfOnline()
            server.takeRequest(2, TimeUnit.SECONDS)

            val afterPull = dao.getRowById(localId)!!
            val blocks = com.example.notes.ui.edit.parseBlocksFromContent(afterPull.content)
            val textBlock = blocks.filterIsInstance<com.example.notes.ui.edit.ContentBlock.TextBlock>().first()
            val imgBlock = blocks.filterIsInstance<com.example.notes.ui.edit.ContentBlock.ImageBlock>().first()

            assertEquals(
                "text block must be updated from server",
                "server updated text",
                textBlock.text
            )
            assertEquals(
                "image block must preserve local file:// URI instead of pending-image marker",
                localUri,
                imgBlock.uri
            )

            // Verify LocalImageCleanup does not delete the local file.
            com.example.notes.util.LocalImageCleanup.runNow(context, dao)
            assertTrue(
                "LocalImageCleanup must keep the referenced local file on disk",
                localFile.exists()
            )
        } finally {
            localFile.delete()
        }
    }

    /**
     * Build a note's content the way the editor does: a text block
     * plus an image block, serialized through the shared
     * `serializeBlocksToContent` (JSON v2). Using the real
     * serializer instead of hand-written JSON matters because the
     * Robolectric temp path contains backslashes, which have to be
     * escaped for the JSON to stay parseable.
     */
    private fun noteContentWithImage(imageUri: String, text: String): String =
        com.example.notes.ui.edit.serializeBlocksToContent(
            listOf(
                com.example.notes.ui.edit.ContentBlock.TextBlock(text = text),
                com.example.notes.ui.edit.ContentBlock.ImageBlock(uri = imageUri)
            )
        )

    /** Drive a note's image-upload retry counter to its cap. */
    private fun burnRetryBudget(noteId: Long) {
        com.example.notes.util.ImageRetryStore.reset(context, noteId)
        repeat(com.example.notes.util.ImageRetryStore.MAX_RETRIES) {
            com.example.notes.util.ImageRetryStore
                .incrementAndCheck(context, noteId)
        }
    }

    private fun upsertOk(serverId: String, changeId: Long): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                {
                  "note": {
                    "id": "$serverId",
                    "title": "x",
                    "content": "x",
                    "is_favorite": 0,
                    "is_trashed": 0,
                    "background_color": 0,
                    "client_updated_at": 1700000000000,
                    "created_at": 1700000000000,
                    "change_id": $changeId
                  }
                }
                """.trimIndent()
            )

    /**
     * Build a paginated `/notes?since=N` response with [count] notes
     * and the supplied `has_more` flag. Each note gets a
     * deterministic server-side id so the test can assert on
     * which ones made it into the local DB.
     */
    private fun listResponse(count: Int, syncCursor: Long, hasMore: Boolean): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(buildListResponseJson(count, syncCursor, hasMore))

    private fun buildListResponseJson(count: Int, syncCursor: Long, hasMore: Boolean): String {
        val sb = StringBuilder()
        sb.append("{\"notes\":[")
        for (i in 0 until count) {
            val id = "00000000-0000-0000-0000-00000000%04d".format(i)
            if (i > 0) sb.append(',')
            sb.append(
                "{\"id\":\"$id\",\"title\":\"t$i\",\"content\":\"c$i\"," +
                    "\"is_favorite\":0,\"is_trashed\":0,\"background_color\":0," +
                    "\"client_updated_at\":1700000000000,\"created_at\":1700000000000," +
                    "\"change_id\":${syncCursor + i + 1}}"
            )
        }
        sb.append("],\"sync_timestamp\":1700000000000,")
        sb.append("\"sync_cursor\":$syncCursor,")
        sb.append("\"has_more\":$hasMore}")
        return sb.toString()
    }

    /**
     * F-03 — pull pagination. The server's `?since=N` endpoint caps a
     * single response and signals "more pages exist" via
     * [com.example.notes.data.remote.NotesListResponse.has_more].
     * The client must loop until `has_more` is false, persisting
     * `sync_cursor` after every page so an interrupted pull can
     * resume.
     */
    @Test
    fun `F-03 pull loops on has_more and saves cursor after every page`() = runBlocking {
        // Three pages of 5 notes each; only the last page signals
        // has_more=false. Smaller pages keep the test fast and
        // still exercise the loop boundary.
        val pageSize = 5
        server.enqueue(listResponse(count = pageSize, syncCursor = 5, hasMore = true))
        server.enqueue(listResponse(count = pageSize, syncCursor = 10, hasMore = true))
        server.enqueue(listResponse(count = pageSize, syncCursor = 15, hasMore = false))

        repo.syncIfOnline()

        val recorded = run {
            val r1 = server.takeRequest(2, TimeUnit.SECONDS)
            val r2 = server.takeRequest(2, TimeUnit.SECONDS)
            val r3 = server.takeRequest(2, TimeUnit.SECONDS)
            listOfNotNull(r1, r2, r3)
        }
        assertEquals(
            "pull must hit the server 3 times for 3 pages",
            3, recorded.size
        )
        // First request: no `since` (fresh pull from a user that
        // never synced). The repository's `getLastCursor` returns
        // null so the URL is just `/notes`.
        val firstPath = recorded[0].path!!
        assertTrue(
            "first page must NOT have a `since` param (got $firstPath)",
            !firstPath.contains("since=")
        )
        // Subsequent pages MUST include `since=<cursor>` so the
        // server can keep them stateless.
        assertTrue(
            "second page must include since=5 (got ${recorded[1].path})",
            recorded[1].path!!.contains("since=5")
        )
        assertTrue(
            "third page must include since=10 (got ${recorded[2].path})",
            recorded[2].path!!.contains("since=10")
        )
        // The cursor was persisted after every page, so the last
        // SyncStore value must be the cursor of the final page.
        assertEquals(
            "cursor must advance to the last page's value",
            15L, SyncStore.getLastCursor(context, userId = 3)
        )
    }

    /**
     * F-03 — pull hits the safety cap. A buggy server that always
     * returns has_more=true must NOT cause the client to loop
     * forever. The pull logs a warning and stops at the cap; the
     * persisted cursor means the next sync picks up from where we
     * stopped.
     */
    @Test
    fun `F-03 pull stops at the page cap rather than looping forever`() = runBlocking {
        // Enqueue 101 pages so the cap is hit. MockWebServer has a
        // default body queue size that's plenty for this.
        repeat(101) { i ->
            val cursor = ((i + 1) * 200L)
            server.enqueue(
                listResponse(count = 1, syncCursor = cursor, hasMore = true)
            )
        }

        repo.syncIfOnline()

        // The pull must have stopped at exactly 100 requests (the
        // cap). The 101st would mean the cap didn't fire.
        val requestCount = (1..101).count { i ->
            server.takeRequest(50, TimeUnit.MILLISECONDS) != null
        }
        assertEquals(
            "pull must stop at the 100-page safety cap",
            100, requestCount
        )
    }

    /**
     * B. 410 Gone resync: When server returns HTTP 410 on GET /notes?since=...
     * (stale cursor), NotesSyncRepository.runPull drops/resets the user's sync
     * cursor to 0 and performs a full pull from scratch, which succeeds.
     */
    @Test
    fun `410 Gone resets cursor to 0 and re-pulls full list successfully`() = runBlocking {
        // Pre-condition: user has an existing cursor from a previous sync.
        SyncStore.setLastCursor(context, userId = 3, cursor = 42L)
        assertEquals(42L, SyncStore.getLastCursor(context, userId = 3))

        // First request receives 410 Gone (stale cursor).
        server.enqueue(
            MockResponse()
                .setResponseCode(410)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"Sync cursor expired"}""")
        )

        // Retry request receives full list (since=null).
        server.enqueue(
            listResponse(count = 3, syncCursor = 100L, hasMore = false)
        )

        val outcome = repo.syncIfOnline()
        assertEquals(NotesSyncRepository.SyncOutcome.Success, outcome)

        // Check requests sent to MockWebServer:
        // 1st request had ?since=42
        val r1 = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(r1)
        assertTrue("first request must have since=42", r1!!.path!!.contains("since=42"))

        // 2nd request had no since param (full pull from scratch)
        val r2 = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(r2)
        assertFalse("second request must NOT have since param", r2!!.path!!.contains("since="))

        // Ingested 3 notes into DAO
        val liveNotes = dao.allRows().filter { it.userId == 3 && it.deletedAt == null }
        assertEquals(3, liveNotes.size)

        // Cursor in SyncStore is updated to 100L
        assertEquals(100L, SyncStore.getLastCursor(context, userId = 3))
    }

    @Test
    fun `410 Gone second failure terminates pull with Failed outcome`() = runBlocking {
        SyncStore.setLastCursor(context, userId = 3, cursor = 42L)

        server.enqueue(MockResponse().setResponseCode(410).setBody("""{"error":"Gone"}"""))
        server.enqueue(MockResponse().setResponseCode(410).setBody("""{"error":"Gone"}"""))

        val outcome = repo.syncIfOnline()
        assertTrue(outcome is NotesSyncRepository.SyncOutcome.Failed)
    }

    // ---------- In-memory test double for NoteDao (B6-flavoured) ----------

    /**
     * Minimal in-memory [NoteDao] for the MockWebServer-driven tests.
     * Same shape as the one in [NotesSyncRepositoryTest], kept private
     * here so the two suites can evolve independently.
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
            rows.firstOrNull { it.serverId == serverId }

        override suspend fun getDirtyNotes(userId: Int): List<NoteEntity> =
            rows.filter {
                it.userId == userId &&
                it.dirtyRevision > it.lastSyncedRevision
            }
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
                    baseTitle = baseTitle,
                    baseContent = baseContent
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