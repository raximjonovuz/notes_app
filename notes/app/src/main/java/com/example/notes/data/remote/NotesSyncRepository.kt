package com.example.notes.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.notes.data.local.AuthStore
import com.example.notes.data.local.NoteDao
import com.example.notes.data.local.NoteEntity
import com.example.notes.data.local.SyncStore
import com.example.notes.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * V5.3 — bidirectional sync between local Room and the V5.3 backend.
 *
 * 8 architectural fixes from the V5.2 audit:
 *   1. Push is keyed on `dirtyRevision > lastSyncedRevision` instead
 *      of `serverId IS NULL`. Trash/pin/color flips on already-synced
 *      rows are now actually uploaded.
 *   2. Deletes are soft on both sides. The local row stays in the
 *      `notes` table with `deletedAt` set; the server marks its row
 *      with `deleted_at`. The local row is purged only after a pull
 *      sees the server-side tombstone (serverChangeId > 0).
 *   3. 3-way merge on pull. `baseTitle` / `baseContent` is the last
 *      version the server confirmed. If local and server diverge and
 *      we can't auto-merge, we mark the local row `is_conflict=1` and
 *      keep both versions — no silent overwrite.
 *   4. Pull cursor is the server's monotonic `change_id` from
 *      `change_log`, not a wall-clock millisecond.
 *   5. Server now stores `client_updated_at` (no MAX, no
 *      `Date.now()`), so clock skew can never pin a stale version
 *      on top of a fresh one.
 *   6. R2 URLs are auth-gated and per-user; `/files/<key>` checks
 *      the `images` table before serving.
 *   7. Image upload compresses to JPEG q=85 with a 6 MB cap.
 *   8. A queued push is recorded in `SyncStore.pendingFlushVersion`
 *      so a cold start can detect "previous process died mid-push" and
 *      flush immediately.
 */
class NotesSyncRepository(
    private val noteDao: NoteDao,
    private val context: Context
) {

    private val activeUserId: Int
        get() = AuthStore.getActive(context)?.userId ?: 0

    private val tag = "NotesSyncRepository"

    internal fun hasPendingLocalImage(content: String): Boolean =
        content.contains(localFileUriPrefix)

    internal val localFileUriPrefix: String by lazy {
        "file://" + context.filesDir.absolutePath + "/"
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Public entry point used by the editor/list/sync button. Runs a
     * push pass first (to drain local edits), then a pull pass.
     */
    suspend fun syncIfOnline(): Boolean = withContext(Dispatchers.IO) {
        if (!isOnline()) {
            Logger.d(tag, "syncIfOnline: device offline, skipping")
            return@withContext false
        }
        val session = AuthStore.getActive(context)
        if (session == null) {
            Logger.d(tag, "syncIfOnline: no session, skipping")
            return@withContext false
        }
        pushLocalChangesInternal()
        pullRemoteChangesInternal()
    }

    /** Internal: pull uses the monotonic server cursor (BUG 4). */
    private suspend fun pullRemoteChangesInternal(): Boolean {
        val cursor = SyncStore.getLastCursor(context)
        val response = ApiClient.listNotes(cursor).getOrElse {
            Logger.w(tag, "pullRemoteChanges: listNotes failed", it)
            return false
        }
        try {
            for (note in response.notes) {
                mergeServerNote(note)
            }
            // Persist the new cursor so the next pull is incremental.
            // If the server returned 0 (no rows touched since last pull),
            // still bump the cursor to whatever the server said so we
            // don't re-fetch forever.
            if (response.sync_cursor > 0) {
                SyncStore.setLastCursor(context, response.sync_cursor)
            }
            // BUG 2 — purge rows whose tombstone has been seen on the server.
            purgeAcknowledgedDeletes()
            return true
        } catch (t: Throwable) {
            Logger.w(tag, "pullRemoteChanges: DB merge failed", t)
            return false
        }
    }

    /**
     * V5.3 — 3-way merge for a single server row. Four cases:
     *
     * 1. Local row with same serverId and `dirtyRevision ==
     *    lastSyncedRevision` (no local edits): simply take the
     *    server's content, refresh base.
     *
     * 2. Local row with same serverId but locally dirty: 3-way merge
     *    against `baseTitle/baseContent`. If mergeable, take the
     *    union, set dirtyRevision = lastSyncedRevision so the next
     *    push uploads the merged version. If not mergeable, mark the
     *    local row `is_conflict=1` with `conflict_original_id` set to
     *    the server note's id; create a *new* local row that is the
     *    server's view (this is the canonical side, the local copy
     *    lives on as the "user's diverged version" with is_conflict).
     *
     * 3. No local row by serverId, but a (title, content, userId)
     *    match against a local row with `serverId == null` —
     *    backfill the serverId (legacy path, kept for the
     *    V5.0 migration window).
     *
     * 4. No match at all: insert.
     *
     * Tombstones (deleted_at != null) are handled separately: the
     * local row is marked `deletedAt` (if it exists by serverId) so a
     * subsequent push can confirm the soft-delete on the server.
     */
    private suspend fun mergeServerNote(note: NoteSyncDto) {
        val local = noteDao.getNoteByServerId(note.id)
        if (note.deleted_at != null) {
            if (local != null) {
                noteDao.softDeleteNote(local.id, note.deleted_at)
            }
            return
        }

        if (local != null) {
            // Case 1: clean local copy, take server's truth.
            if (local.dirtyRevision <= local.lastSyncedRevision) {
                noteDao.updateNote(
                    local.copy(
                        title = note.title,
                        content = note.content,
                        isFavorite = note.is_favorite != 0,
                        isTrashed = note.is_trashed != 0,
                        backgroundColor = note.background_color,
                        serverUpdatedAt = note.client_updated_at,
                        serverChangeId = note.change_id,
                        baseTitle = note.title,
                        baseContent = note.content
                    )
                )
                Logger.d(tag, "mergeServerNote: clean update serverId=${note.id}")
                return
            }
            // Case 2: local dirty. 3-way merge.
            val merged = tryThreeWayMerge(local, note)
            noteDao.updateNote(merged)
            return
        }

        // Case 3: legacy content match backfill.
        val byContent = noteDao.findByContentAndUser(
            title = note.title,
            content = note.content,
            userId = activeUserId
        )
        if (byContent != null && byContent.serverId == null) {
            noteDao.updateNote(
                byContent.copy(
                    serverId = note.id,
                    serverChangeId = note.change_id,
                    serverUpdatedAt = note.client_updated_at,
                    baseTitle = note.title,
                    baseContent = note.content,
                    isConflict = false
                )
            )
            Logger.d(tag, "mergeServerNote: backfill serverId=${note.id} on localId=${byContent.id}")
            return
        }

        // Case 4: brand-new row.
        noteDao.insertNote(
            note.toEntity(userId = activeUserId).copy(
                lastSyncedRevision = 1,   // already in sync with the server
                dirtyRevision = 1,
                baseTitle = note.title,
                baseContent = note.content,
                serverChangeId = note.change_id,
                serverUpdatedAt = note.client_updated_at
            )
        )
        Logger.d(tag, "mergeServerNote: inserted new serverId=${note.id}")
    }

    /**
     * 3-way merge. Strategy:
     *   - If base == local, take the server side (we never had an edit
     *     that didn't sync). Update dirtyRevision so the local row
     *     reflects the new content.
     *   - If base == server, take the local side (the server's view
     *     is stale because the local edit didn't reach it). Leave
     *     dirtyRevision as-is so the next push re-asserts our content.
     *   - If all three differ and the title is exactly the same, we
     *     can do a line-based union by simple concatenation: take
     *     server's content as the canonical trunk and append the
     *     unique lines from local. (Cheap approximation; good enough
     *     for prose notes.)
     *   - Otherwise: keep both. Mark `is_conflict = true`. The
     *     `conflict_original_id` field is the server note's id.
     */
    private suspend fun tryThreeWayMerge(local: NoteEntity, server: NoteSyncDto): NoteEntity {
        val baseTitle = local.baseTitle
        val baseContent = local.baseContent

        if (baseTitle == null || baseContent == null) {
            // V5.3.1 — first sync after upgrade, OR a brand-new note
            // whose push just landed, OR a note that has never been
            // pulled. We have no common ancestor.
            //
            // The earlier V5.3.0 behaviour marked the row as
            // `isConflict = true` and left the content untouched —
            // which collided with the "fresh push" case: the next pull
            // would see the same null-base, re-mark conflict, and the
            // server's R2 URL (e.g. `https://…/files/uploads/…`) would
            // NEVER be able to overwrite the local `file://…` URI
            // because pull always landed in Case 2. Symptom: the
            // freshly-uploaded image stayed in the note until the user
            // manually retyped the body.
            //
            // The middle V5.3.1 attempt took the server's content
            // unconditionally — that *broke in the opposite direction*
            // when the server still held the pre-upload `file://…`
            // copy (because the push had been delayed by the
            // `ready` filter): the pull would silently replace the
            // user's `https://…` URL with the server's stale
            // `file://…` URI and the image would vanish.
            //
            // Correct rule: in the absence of a common ancestor, the
            // local copy is authoritative. Trust the user. Seed
            // `baseTitle` / `baseContent` from the local row so
            // subsequent 3-way merges have a real ancestor, update
            // the server-side metadata pointers, and let the next
            // push reconcile whatever divergence the server is
            // holding. The dirty bit stays set so the push fires.
            return local.copy(
                serverChangeId = server.change_id,
                serverUpdatedAt = server.client_updated_at,
                baseTitle = local.title,
                baseContent = local.content,
                isConflict = false,
                conflictOriginalId = null
            )
        }

        val baseEqualLocal = baseTitle == local.title && baseContent == local.content
        val baseEqualServer = baseTitle == server.title && baseContent == server.content
        when {
            baseEqualLocal -> {
                // Local hadn't diverged from base; the server is the
                // new truth.
                return local.copy(
                    title = server.title,
                    content = server.content,
                    isFavorite = server.is_favorite != 0,
                    isTrashed = server.is_trashed != 0,
                    backgroundColor = server.background_color,
                    serverChangeId = server.change_id,
                    serverUpdatedAt = server.client_updated_at,
                    baseTitle = server.title,
                    baseContent = server.content,
                    isConflict = false,
                    conflictOriginalId = null,
                    // Bump lastSyncedRevision so the dirty check no
                    // longer fires for this content.
                    lastSyncedRevision = local.dirtyRevision
                )
            }
            baseEqualServer -> {
                // The server's view is stale; keep our local edits and
                // let the next push re-assert.
                return local.copy(
                    serverChangeId = server.change_id,
                    serverUpdatedAt = server.client_updated_at,
                    isConflict = false,
                    conflictOriginalId = null
                )
            }
            else -> {
                // Both diverged from the common ancestor. Cheap
                // line-based auto-merge.
                val mergedContent = lineUnion(baseContent, local.content, server.content)
                val mergedTitle = if (local.title == baseTitle) server.title
                                  else if (server.title == baseTitle) local.title
                                  else local.title + " / " + server.title
                val mergeable = mergedContent != null
                return if (mergeable) {
                    local.copy(
                        title = mergedTitle,
                        content = mergedContent,
                        serverChangeId = server.change_id,
                        serverUpdatedAt = server.client_updated_at,
                        baseTitle = baseTitle,
                        baseContent = baseContent,
                        isConflict = false,
                        conflictOriginalId = null,
                        // dirtyRevision stays > lastSyncedRevision so
                        // the merged result is re-pushed.
                        lastSyncedRevision = local.lastSyncedRevision
                    )
                } else {
                    // Hard conflict. We mark the local row as the
                    // "user's diverged version" and create a new local
                    // row that is the canonical server view. Both are
                    // visible to the user (the conflict one is shown
                    // with a banner).
                    noteDao.insertNote(
                        NoteEntity(
                            title = server.title,
                            content = server.content,
                            isFavorite = server.is_favorite != 0,
                            isTrashed = server.is_trashed != 0,
                            backgroundColor = server.background_color,
                            serverId = server.id,
                            userId = activeUserId,
                            serverChangeId = server.change_id,
                            serverUpdatedAt = server.client_updated_at,
                            baseTitle = server.title,
                            baseContent = server.content,
                            dirtyRevision = 1,
                            lastSyncedRevision = 1
                        )
                    )
                    local.copy(
                        isConflict = true,
                        conflictOriginalId = server.id
                    )
                }
            }
        }
    }

    /**
     * Three-way union by line. Returns null if any line in `local` or
     * `server` has no overlap with the base (i.e. cannot be merged
     * safely without losing data). Lines are compared by `trim()`.
     */
    private fun lineUnion(base: String, local: String, server: String): String? {
        val baseLines = base.lines().map { it.trim() }.toSet()
        val localLines = local.lines()
        val serverLines = server.lines()
        val localUnique = localLines.filter { it !in baseLines && it.isNotBlank() }
        val serverUnique = serverLines.filter { it !in baseLines && it.isNotBlank() }
        if (localUnique.any { it in serverUnique }) {
            // Both sides added the same line — fine to keep.
            val shared = localUnique.intersect(serverUnique.toSet())
            val out = LinkedHashSet<String>()
            out.addAll(serverLines)
            out.addAll(localLines.filter { it !in baseLines && it !in shared })
            return out.joinToString("\n")
        }
        if (localUnique.isEmpty() && serverUnique.isEmpty()) return base
        if (localUnique.isEmpty()) return server
        if (serverUnique.isEmpty()) return local
        // No overlap at all — risk of overlap, refuse auto-merge.
        return null
    }

    /**
     * Drop local rows whose soft-delete has been confirmed by the
     * server (the server has issued a `change_log` entry for it and we
     * saw that entry in this pull).
     */
    private suspend fun purgeAcknowledgedDeletes() {
        val toPurge = noteDao.getDirtyNotes(activeUserId)
            .filter { it.deletedAt != null && it.serverChangeId > 0 }
        for (row in toPurge) {
            noteDao.purgeNoteById(row.id)
            Logger.d(tag, "purged soft-deleted note localId=${row.id} serverId=${row.serverId}")
        }
    }

    private val pushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingPushJob: Job? = null
    private val pushMutex = Mutex()

    /** Drain the local push queue. Public for the worker. */
    suspend fun pushLocalChanges(): Int = withContext(Dispatchers.IO) {
        pushMutex.withLock { pushLocalChangesInternal() }
    }

    /**
     * V5.3 — debounced push (BUG 8 hardening). Bumps the persistent
     * pendingFlushVersion first; if the process dies before the
     * coroutine actually runs, the cold-start re-launches the push.
     */
    fun pushLocalChangesDebounced() {
        synchronized(this) {
            pendingPushJob?.cancel()
            SyncStore.bumpPendingFlush(context)
            pendingPushJob = pushScope.launch {
                delay(PUSH_DEBOUNCE_MS)
                pushMutex.withLock {
                    pushLocalChangesInternal()
                }
            }
        }
    }

    /**
     * V5.3 — image-upload retry. When a push is filtered because a note
     * still embeds a local `file://` URI (i.e. the R2 upload is in flight
     * or failed), schedule a follow-up push so the rewritten URL is
     * eventually synced. Keeps `pendingFlush` version alive so
     * [coldStartFlushIfNeeded] also picks it up across process death.
     */
    private fun schedulePendingImagePush() {
        synchronized(this) {
            pendingPushJob?.cancel()
            SyncStore.bumpPendingFlush(context)
            pendingPushJob = pushScope.launch {
                delay(PUSH_DEBOUNCE_MS)
                pushMutex.withLock {
                    pushLocalChangesInternal()
                }
            }
        }
    }

    /** Flush the debounced push immediately, used by sync button + worker. */
    suspend fun flushPendingPush(): Int = withContext(Dispatchers.IO) {
        synchronized(this@NotesSyncRepository) {
            pendingPushJob?.cancel()
            pendingPushJob = null
        }
        pushMutex.withLock { pushLocalChangesInternal() }
    }

    /**
     * Drain `notes` where `dirtyRevision > lastSyncedRevision`. Two
     * kinds of pushes:
     *   - Live (deletedAt == null) → POST /notes (UPSERT).
     *   - Tombstoned (deletedAt != null) → DELETE /notes/<uuid>.
     *
     * We do live pushes first, then tombstones, so a row that gets
     * undeleted between the SELECT and the push doesn't end up as a
     * DELETE. (A true race is still possible but the next push
     * round-trip converges.)
     */
    private suspend fun pushLocalChangesInternal(): Int {
        if (!isOnline()) {
            Logger.d(tag, "pushLocalChanges: offline, skipping")
            return 0
        }
        val session = AuthStore.getActive(context)
        if (session == null) {
            Logger.d(tag, "pushLocalChanges: no session, skipping")
            return 0
        }

        val dirty = noteDao.getDirtyNotes(activeUserId)
        if (dirty.isEmpty()) {
            SyncStore.consumePendingFlush(context)
            return 0
        }

        val live = dirty.filter { it.deletedAt == null }
        val tombstones = dirty.filter { it.deletedAt != null }

        // Don't push notes that still embed a local file:// image.
        val ready = live.filterNot { it.content.contains(localFileUriPrefix) }
        if (ready.isEmpty() && tombstones.isEmpty()) {
            Logger.d(
                tag,
                "pushLocalChanges: ${live.size} note(s) deferred for image upload, no tombstones"
            )
            if (live.any { it.content.contains(localFileUriPrefix) }) {
                schedulePendingImagePush()
            } else {
                SyncStore.consumePendingFlush(context)
            }
            return 0
        }

        var pushed = 0
        for (note in ready) {
            val now = System.currentTimeMillis()
            val dto = NoteSyncDto(
                id = note.serverId ?: java.util.UUID.randomUUID().toString(),
                title = note.title,
                content = note.content,
                is_favorite = if (note.isFavorite) 1 else 0,
                is_trashed = if (note.isTrashed) 1 else 0,
                background_color = note.backgroundColor,
                client_updated_at = note.updatedAt,
                created_at = note.createdAt
            )
            val result = ApiClient.upsertNote(dto)
            result.fold(
                onSuccess = { response ->
                    // markSynced updates lastSyncedRevision = dirtyRevision,
                    // baseTitle/baseContent, serverChangeId and
                    // serverUpdatedAt in a single SQL UPDATE.
                    noteDao.markSynced(
                        localId = note.id,
                        syncedRev = note.dirtyRevision,
                        serverChangeId = response.change_id,
                        serverUpdatedAt = response.client_updated_at
                    )
                    // Update serverId on the local row (idempotent; markSynced
                    // doesn't touch it). A separate updateNote so the
                    // serverId is the value the server echoed back.
                    if (note.serverId != response.id) {
                        noteDao.updateNote(note.copy(serverId = response.id))
                    }
                    pushed++
                    Logger.d(tag, "pushLocalChanges: pushed note ${note.id} -> ${response.id}")
                },
                onFailure = { e ->
                    Logger.w(tag, "pushLocalChanges: upsertNote failed for ${note.id}", e)
                }
            )
        }

        for (note in tombstones) {
            val serverId = note.serverId
            if (serverId == null) {
                // Local note never made it to the server. Just drop it.
                noteDao.purgeNoteById(note.id)
                continue
            }
            val ok = ApiClient.deleteNote(serverId).isSuccess
            if (ok) {
                // Don't purge yet — we need a pull to confirm the
                // server-side change_id. The pull side will
                // purgeAcknowledgedDeletes() once it sees the tombstone.
                Logger.d(tag, "pushLocalChanges: server delete ok for localId=${note.id}")
                pushed++
            } else {
                Logger.w(tag, "pushLocalChanges: server delete failed for localId=${note.id}")
            }
        }

        SyncStore.consumePendingFlush(context)
        return pushed
    }

    /**
     * V5.3 — BUG 8. Called once on cold start. If a previous process
     * bumped the pendingFlushVersion but was killed before draining it,
     * fire a push right away.
     */
    fun coldStartFlushIfNeeded() {
        val pending = SyncStore.consumePendingFlush(context)
        if (pending > 0L) {
            Logger.d(tag, "coldStartFlushIfNeeded: pending=$pending — scheduling flush")
            pushScope.launch {
                pushMutex.withLock { pushLocalChangesInternal() }
            }
        }
    }

    suspend fun deleteOnServer(serverId: String): Boolean = withContext(Dispatchers.IO) {
        if (!isOnline()) {
            Logger.d(tag, "deleteOnServer: offline, skipping")
            return@withContext false
        }
        if (AuthStore.getActive(context) == null) {
            Logger.d(tag, "deleteOnServer: no session, skipping")
            return@withContext false
        }
        ApiClient.deleteNote(serverId).fold(
            onSuccess = { true },
            onFailure = { e ->
                Logger.w(tag, "deleteOnServer: $serverId failed", e)
                false
            }
        )
    }

    private fun NoteSyncDto.toEntity(userId: Int): NoteEntity {
        val now = System.currentTimeMillis()
        return NoteEntity(
            id = 0,
            title = title,
            content = content,
            createdAt = if (created_at > 0) created_at else now,
            updatedAt = if (client_updated_at > 0) client_updated_at else now,
            isFavorite = is_favorite != 0,
            isTrashed = is_trashed != 0,
            backgroundColor = background_color,
            fontWeight = 0,
            textSize = 0,
            imageUris = "",
            serverId = id,
            userId = userId,
            deletedAt = deleted_at,
            serverChangeId = change_id,
            serverUpdatedAt = client_updated_at
        )
    }

    companion object {
        private const val PUSH_DEBOUNCE_MS = 5_000L
    }
}
