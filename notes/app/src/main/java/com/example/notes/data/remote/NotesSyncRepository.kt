package com.example.notes.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.notes.data.local.AuthStore
import com.example.notes.data.local.NoteDao
import com.example.notes.data.local.NoteEntity
import com.example.notes.data.local.SyncStore
import com.example.notes.util.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

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
 *
 * V5.3.4 / B1 — tombstone push no longer waits for a confirming pull.
 *   The push DELETE is a hard server-side delete; on 200 OR 404 the
 *   local row is purged in the same pass. The
 *   `(deletedAt IS NULL OR serverChangeId = 0)` filter on
 *   `getDirtyNotes` is gone, so synced tombstones finally make it
 *   into the push queue. Tombstones received from a `since` pull are
 *   also purged immediately — no two-phase "soft-delete locally,
 *   wait for a second pull" dance.
 *
 * V5.3.4 / B3 — `serverId` is a local UUID generated at insert time.
 *   The push POST body carries the same UUID the local row already
 *   holds, the server echoes it back, and the local row's `serverId`
 *   never changes after insert. The legacy "set serverId from the
 *   response" update is gone.
 *
 * V5.3.4 / B6 — sync state (cursor + pending flush) is namespaced
 *   per user. Every sync entry point captures `userId` once and
 *   passes it through, instead of reading `AuthStore.getActive` on
 *   every SharedPreferences call. The single `syncMutex` serialises
 *   push and pull so a switch-account mid-sync cannot interleave
 *   a partial push from one user with a pull for another.
 */
class NotesSyncRepository(
    private val noteDao: NoteDao,
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val pushScope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher)
) {

    private val tag = "NotesSyncRepository"

    /**
     * B6 — single sync mutex used for both push and pull. The previous
     * code only had a push mutex, so a push could be running while a
     * pull was in flight; combined with the now-per-user cursor that
     * meant a switch-account between the two could mix cursors. With
     * one mutex the whole round-trip is atomic w.r.t. other syncs.
     */
    private val syncMutex = Mutex()

    /**
     * B2 — typed sync result so the caller (list VM, background worker)
     * can tell apart a network blip (worth retrying), a 401 (the
     * session is dead, surface a "please log in" hint), and a clean
     * round-trip. Replaces the previous `Boolean` return where a 401
     * looked like just another failure.
     */
    sealed class SyncOutcome {
        /** Both pull and push finished without an error. */
        object Success : SyncOutcome()

        /**
         * Network/5xx/timeout — the next attempt will probably succeed.
         * [reason] is the underlying error message (already passed
         * through [Logger.w] with the token masked).
         */
        data class Failed(val reason: String?) : SyncOutcome()

        /**
         * 401 Unauthorized — the active session is no longer valid.
         * Side-effect: the local active session has been cleared so
         * the next [AuthStore.getActive] returns null.
         */
        object Unauthorized : SyncOutcome()

        /** No active user, device offline, or sync was skipped. */
        object Skipped : SyncOutcome()
    }

    internal fun hasPendingLocalImage(content: String): Boolean =
        hasLocalImageUri(content)

    /**
     * F-05 — true when [content] still references at least one image
     * that lives ONLY on this device (a `file://<filesDir>/…` URI).
     * Such a note must not be pushed as-is: the `file://` path is
     * meaningless on the server and on every other device.
     *
     * Both the current prefix (`filesDir/images/`) and the legacy one
     * (`filesDir/`, pre-F-28) are recognised, so a note that hasn't
     * been through [LocalImageMigration] yet is still handled
     * correctly.
     */
    private fun hasLocalImageUri(content: String): Boolean {
        if (content.contains(localFileUriPrefix)) return true
        // F-28 migration — accept the legacy `file://<filesDir>/img_*`
        // form too. After [LocalImageMigration] rewrites every note's
        // content to the new prefix, this branch never matches, but
        // during the migration window (and on installs that haven't run
        // the migration yet) the old URI must still defer the push;
        // otherwise the editor would push a `file://…/img_X.png` URI
        // to the server before the R2 upload finishes.
        if (content.contains(legacyLocalFileUriPrefix + "img_")) return true
        if (content.contains("file://")) {
            val blocks = com.example.notes.ui.edit.parseBlocksFromContent(content)
            return blocks.any { block ->
                block is com.example.notes.ui.edit.ContentBlock.ImageBlock &&
                    com.example.notes.util.LocalImageUri.isLocalImageUri(block.uri, context)
            }
        }
        return false
    }

    /**
     * The new prefix the editor uses to insert image blocks (since
     * F-28 the file lives under `filesDir/images/`, not `filesDir/`).
     */
    internal val localFileUriPrefix: String by lazy {
        com.example.notes.util.LocalImageUri.newPrefix(context)
    }

    /**
     * F-28 — kept only for [hasPendingLocalImage]'s legacy fallback.
     * New code should always go through [localFileUriPrefix].
     */
    private val legacyLocalFileUriPrefix: String by lazy {
        com.example.notes.util.LocalImageUri.legacyPrefix(context)
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * B6 — read the active userId once. Callers that need to keep the
     * value stable across a sync pass capture this at the start and
     * re-check at strategic points (push/pull boundaries) so a
     * mid-sync account switch aborts cleanly.
     */
    private fun currentUserId(): Int = AuthStore.getActive(context)?.userId ?: 0

    private fun abortIfUserChanged(expected: Int, op: String): Boolean {
        val now = currentUserId()
        if (now != expected) {
            Logger.d(
                tag,
                "$op: userId changed ($expected -> $now), aborting"
            )
            return true
        }
        return false
    }

    /**
     * Public entry point used by the editor/list/sync button. Runs a
     * **pull** pass first (to discover any server-side changes and
     * resolve conflicts against the local row), then a **push** pass
     * (so the merged result is the one that goes up). B2: the previous
     * order (push → pull) meant a conflict could be detected only on
     * the *next* round-trip, with the user's local edits momentarily
     * overwriting the server's before being clobbered back.
     */
    suspend fun syncIfOnline(): SyncOutcome = withContext(ioDispatcher) {
        if (!isOnline()) {
            Logger.d(tag, "syncIfOnline: device offline, skipping")
            return@withContext SyncOutcome.Skipped
        }
        val userId = currentUserId()
        if (userId <= 0) {
            Logger.d(tag, "syncIfOnline: no session, skipping")
            return@withContext SyncOutcome.Skipped
        }
        return@withContext syncMutex.withLock {
            val pullResult = runPull(userId)
            if (pullResult is SyncOutcome.Unauthorized || pullResult is SyncOutcome.Failed) {
                // Don't try to push if the server already rejected us as
                // unauthenticated — every push would just 401. On a
                // transient failure, also skip the push; the next manual
                // or background sync will retry both.
                return@withLock pullResult
            }
            val pushResult = runPush(userId)
            if (pushResult is SyncOutcome.Unauthorized) {
                return@withLock pushResult
            }
            // Combine: if either failed, surface that. Otherwise success.
            if (pullResult is SyncOutcome.Failed) pullResult
            else if (pushResult is SyncOutcome.Failed) pushResult
            else SyncOutcome.Success
        }
    }

    /**
     * B2 — pull helper that turns [ApiClient] failures into the typed
     * [SyncOutcome]. A 401 (the previous [Boolean] return hid it) is
     * treated as "session dead — clear the active pointer so the UI
     * can route the user back to Login".
     *
     * F-03 — pagination loop. The server's `?since=N` endpoint caps a
     * single response at [PULL_PAGE_LIMIT] rows and signals whether
     * more pages exist via [NotesListResponse.has_more]. We loop
     * until `has_more` is false, persisting `sync_cursor` after
     * every page so an interrupted pull (process death, OOM, network
     * blip) resumes from the last successfully-applied page rather
     * than restarting from the beginning.
     *
     * The cap at [PULL_MAX_PAGES] is a defense against a runaway
     * server (e.g. `has_more` always true) or a never-shrinking change
     * log. When the cap is hit we log a warning and stop — the
     * persisted cursor means the next pull picks up where we left
     * off, so a 1 GB DB doesn't take a single sync to ingest.
     *
     * C-2 — 410 Gone means the server-side change_log for this cursor
     * has been pruned by retention (or the cursor is otherwise stale).
     * We drop the cursor back to 0 and re-pull from scratch. A second
     * 410 within the same pass is treated as a hard failure to avoid
     * looping.
     */
    private suspend fun runPull(userId: Int): SyncOutcome {
        val initialCursor = SyncStore.getLastCursor(context, userId)?.takeIf { it > 0 }
        var cursor: Long? = initialCursor
        var pagesProcessed = 0
        var notesIngested = 0
        var hasReset410 = false

        while (true) {
            // Server's `since` parameter expects Long?. The DAO helper
            // returns Long? (null = never synced); the API takes the
            // same shape via the listNotes(since: Long?) overload.
            val listResult = ApiClient.listNotes(cursor)
            val response = if (listResult.isFailure) {
                val err = listResult.exceptionOrNull()!!
                val msg = err.message.orEmpty()
                if (msg.contains("HTTP 410") && !hasReset410) {
                    Logger.w(
                        tag,
                        "pullRemoteChanges: 410 Gone for cursor=$cursor; " +
                            "resetting cursor to 0 and re-pulling from scratch"
                    )
                    hasReset410 = true
                    SyncStore.setLastCursor(context, userId, 0L)
                    cursor = null
                    continue
                }
                return classifyHttpError(err, "pullRemoteChanges")
            } else {
                listResult.getOrThrow()
            }

            try {
                for (note in response.notes) {
                    mergeServerNote(note, userId)
                }
                notesIngested += response.notes.size
                if (response.sync_cursor > 0) {
                    // Persist after every page so an interruption
                    // resumes from the next page, not page 0. The
                    // store's getOrSet semantics make this safe under
                    // concurrent writes.
                    SyncStore.setLastCursor(context, userId, response.sync_cursor)
                }
                purgeAcknowledgedDeletes(userId)
            } catch (t: Throwable) {
                Logger.w(tag,
                    "pullRemoteChanges: DB merge failed at " +
                        "cursor=$cursor page=$pagesProcessed", t)
                return SyncOutcome.Failed(t.message ?: t.javaClass.simpleName)
            }

            pagesProcessed++

            if (!response.has_more) {
                break
            }
            if (pagesProcessed >= PULL_MAX_PAGES) {
                Logger.w(tag,
                    "pullRemoteChanges: hit PULL_MAX_PAGES=$PULL_MAX_PAGES " +
                        "cap after $notesIngested notes; will continue " +
                        "from the saved cursor on the next sync")
                break
            }
            // The next page's cursor is the server's response value
            // (not just the previous cursor + page size) because the
            // server's change_log.id is monotonic, not derivable from
            // an offset.
            cursor = response.sync_cursor.takeIf { it > 0 } ?: cursor
            if (cursor == null) {
                // Defensive: server said has_more but didn't return a
                // cursor. Log and stop to avoid an infinite loop.
                Logger.w(tag,
                    "pullRemoteChanges: server signalled has_more=true " +
                        "without a sync_cursor; stopping to avoid loop")
                break
            }
        }

        Logger.d(tag,
            "pullRemoteChanges: complete pages=$pagesProcessed " +
                "notes=$notesIngested")
        return SyncOutcome.Success
    }

    /**
     * B2 — push helper. Returns [SyncOutcome.Success] when the queue
     * was drained (including the case where there was nothing to
     * push), [SyncOutcome.Unauthorized] on 401, [SyncOutcome.Failed]
     * for any other transport/IO error.
     */
    private suspend fun runPush(userId: Int): SyncOutcome {
        return try {
            pushLocalChangesInternal(userId)
            SyncOutcome.Success
        } catch (t: Throwable) {
            classifyHttpError(t, "pushLocalChanges")
        }
    }

    /**
     * B2 — turns a thrown [Throwable] from an HTTP call into the typed
     * [SyncOutcome]. Recognises `HTTP 401` in the IOException message
     * (the format [ApiClient.execute] uses) and clears the active
     * session so the UI can re-prompt for login.
     */
    private fun classifyHttpError(t: Throwable, op: String): SyncOutcome {
        val msg = t.message.orEmpty()
        if (msg.contains("HTTP 401")) {
            Logger.w(tag, "$op: 401 Unauthorized — clearing active session")
            AuthStore.clearActive(context)
            return SyncOutcome.Unauthorized
        }
        Logger.w(tag, "$op: $msg", t)
        return SyncOutcome.Failed(msg.ifBlank { t.javaClass.simpleName })
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
     * 3. B3 — the legacy `(title, content, userId)` backfill is
     *    removed. With `serverId` assigned at insert time as a local
     *    UUID, no real local row can be left without a serverId, so
     *    a "no local row by serverId but a content match exists"
     *    case no longer happens. Replaced by a direct insert (case
     *    4) for brand-new server rows.
     *
     * 4. No match at all: insert.
     *
     * B1 — tombstone rows (`deleted_at != null`) drop the local copy
     *    immediately via `purgeNoteById`. The previous "soft-delete
     *    locally and let the next push confirm" dance produced rows
     *    that never went away; the new server contract is that
     *    `DELETE /notes/<id>` is a hard server-side delete, so a
     *    tombstone in a pull response is a definitive delete.
     */
    private suspend fun mergeServerNote(note: NoteSyncDto, userId: Int) {
        val local = noteDao.getNoteByServerId(note.id)
        if (note.deleted_at != null) {
            // B1 — server-side tombstone. Purge any local copy right
            // away; the next push won't have anything to send.
            if (local != null) {
                noteDao.purgeNoteById(local.id)
                Logger.d(tag, "mergeServerNote: purged local tombstone serverId=${note.id} localId=${local.id}")
            }
            return
        }

        if (local != null) {
            // Case 1: clean local copy, take server's truth.
            if (local.dirtyRevision <= local.lastSyncedRevision) {
                val mergedContent = preserveLocalImagesForMarkers(local.content, note.content)
                noteDao.updateNote(
                    local.copy(
                        title = note.title,
                        content = mergedContent,
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

        // Case 4: brand-new row. (B3 — case 3 backfill removed; new
        // notes always have a serverId stamped at insert time so
        // there's no "orphan with null serverId" anymore.)
        noteDao.insertNote(
            note.toEntity(userId = userId).copy(
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
     * F-05 — when [serverContent] contains a `pending-image:<basename>`
     * marker and [localContent] still holds a matching local `file://`
     * image block, restore the local `file://` URI on that image block
     * while keeping all text and non-marker blocks from [serverContent].
     */
    private fun preserveLocalImagesForMarkers(
        localContent: String,
        serverContent: String
    ): String {
        if (!serverContent.contains(com.example.notes.util.LocalImageUri.MARKER_PREFIX) ||
            !hasLocalImageUri(localContent)
        ) {
            return serverContent
        }
        val localBlocks = com.example.notes.ui.edit.parseBlocksFromContent(localContent)
        val localUriByMarker = HashMap<String, String>()
        for (block in localBlocks) {
            if (block is com.example.notes.ui.edit.ContentBlock.ImageBlock &&
                com.example.notes.util.LocalImageUri.isLocalImageUri(block.uri, context)
            ) {
                localUriByMarker[com.example.notes.util.LocalImageUri.toMarker(block.uri)] = block.uri
            }
        }
        if (localUriByMarker.isEmpty()) return serverContent

        val serverBlocks = com.example.notes.ui.edit.parseBlocksFromContent(serverContent)
        var mutated = false
        val restored = serverBlocks.map { block ->
            if (block is com.example.notes.ui.edit.ContentBlock.ImageBlock &&
                com.example.notes.util.LocalImageUri.isMarker(block.uri)
            ) {
                val localUri = localUriByMarker[block.uri]
                if (localUri != null) {
                    mutated = true
                    block.copy(uri = localUri)
                } else block
            } else block
        }
        return if (mutated) {
            com.example.notes.ui.edit.serializeBlocksToContent(restored)
        } else {
            serverContent
        }
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

        val sanitizedLocalContent = com.example.notes.util.LocalImageUri
            .sanitizeForWire(local.content, context)
        val baseEqualLocal = baseTitle == local.title &&
            (baseContent == local.content || baseContent == sanitizedLocalContent)
        val baseEqualServer = baseTitle == server.title && baseContent == server.content
        when {
            baseEqualLocal -> {
                // Local hadn't diverged from base; the server is the
                // new truth.
                return local.copy(
                    title = server.title,
                    content = preserveLocalImagesForMarkers(local.content, server.content),
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
                val mergedContent = lineUnion(baseContent, sanitizedLocalContent, server.content)
                    ?.let { preserveLocalImagesForMarkers(local.content, it) }
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
                    // B5 — hard conflict. Two outcomes must coexist:
                    //   1. The user's diverged version is preserved
                    //      with `isConflict = true` and a NEW
                    //      locally-generated `serverId` (so it
                    //      doesn't violate the UNIQUE index, and so a
                    //      later push of it goes as a brand-new note
                    //      rather than stomping the canonical row).
                    //   2. The canonical "what the server has" copy
                    //      is inserted with the original serverId.
                    //
                    // The order matters: we first update the existing
                    // local row so it stops owning the canonical
                    // `serverId` slot, then insert the canonical copy
                    // with that slot. Reversing the two would 500 on
                    // the unique-serverId index.
                    val newLocalServerId = java.util.UUID.randomUUID().toString()
                    noteDao.updateNote(
                        local.copy(
                            serverId = newLocalServerId,
                            isConflict = true,
                            conflictOriginalId = server.id
                        )
                    )
                    noteDao.insertNote(
                        NoteEntity(
                            title = server.title,
                            content = server.content,
                            isFavorite = server.is_favorite != 0,
                            isTrashed = server.is_trashed != 0,
                            backgroundColor = server.background_color,
                            // Canonical row keeps the original serverId
                            // — this is the row a future push would
                            // UPDATE on the server.
                            serverId = server.id,
                            userId = currentUserId(),
                            serverChangeId = server.change_id,
                            serverUpdatedAt = server.client_updated_at,
                            baseTitle = server.title,
                            baseContent = server.content,
                            dirtyRevision = 1,
                            lastSyncedRevision = 1,
                            isConflict = false,
                            conflictOriginalId = null
                        )
                    )
                    // Idempotency: returning the canonical row (the
                    // "real" one) so the caller can update its UI
                    // pointer. The conflict copy is already persisted
                    // above; we don't need to return it.
                    NoteEntity(
                        title = server.title,
                        content = server.content,
                        serverId = server.id,
                        userId = currentUserId(),
                        serverChangeId = server.change_id,
                        serverUpdatedAt = server.client_updated_at,
                        baseTitle = server.title,
                        baseContent = server.content,
                        dirtyRevision = 1,
                        lastSyncedRevision = 1
                    )
                }
            }
        }
    }

    /**
     * B5 — proper 3-way diff by line. Returns null when the two sides
     * added materially different content (hard conflict). Otherwise
     * returns the merged text with **all** lines preserved (no
     * `LinkedHashSet` deduping — the old implementation silently
     * dropped duplicate lines like "Buy milk\nBuy milk" because it
     * fed every line through a Set).
     *
     * Strategy:
     *  1. Split base/local/server into line lists (allowing empty
     *     lines and duplicates).
     *  2. Compute "kept" lines = base lines that were not removed by
     *     either side (i.e. still present in local AND in server).
     *  3. Compute "local additions" = lines in local not in base,
     *     in their original order. Same for "server additions".
     *  4. If the two addition sets are equal (multiset equality —
     *     same elements with the same multiplicities, regardless of
     *     order) we merge by appending them to the kept base.
     *  5. If they differ, conflict (return null). Falling back to a
     *     silent "concatenate both" merge is dangerous: it would
     *     commit the user's local-only content even when the
     *     canonical server has contradictory content.
     *
     * The order in which the additions are appended is the order
     * they appear in the **server** side (which is taken as the
     * canonical chronological ordering). Local additions not in
     * server are appended at the end. This matches the "server is
     * authoritative on shared text" assumption baked into the rest
     * of the merge.
     */
    private fun lineUnion(base: String, local: String, server: String): String? {
        val baseLines = base.lines()
        val localLines = local.lines()
        val serverLines = server.lines()

        // "Kept" = base lines that survived in BOTH local and server.
        // The classic diff3 trick: a base line is "still there" iff
        // it appears at least once in the local copy and at least
        // once in the server copy. We preserve the original base
        // order, not the order in either descendant.
        val localHas = HashSet(localLines)
        val serverHas = HashSet(serverLines)
        val kept = baseLines.filter { it in localHas && it in serverHas }

        // Additions = lines that are in the descendant but not in base.
        // We preserve order (no Set) and allow duplicates.
        val baseHas = HashSet(baseLines)
        val localAdded = localLines.filter { it !in baseHas }
        val serverAdded = serverLines.filter { it !in baseHas }

        // Multiset-equality check: same elements with same multiplicities.
        // We compare sorted lists — order in the descendants doesn't
        // matter for the equality test (we re-apply server order below).
        if (localAdded.sorted() == serverAdded.sorted()) {
            return (kept + serverAdded).joinToString("\n")
        }

        // Subset cases. If one side added nothing, take the other
        // side's view verbatim.
        if (localAdded.isEmpty() && serverAdded.isEmpty()) {
            return kept.joinToString("\n")
        }
        if (localAdded.isEmpty()) {
            return (kept + serverAdded).joinToString("\n")
        }
        if (serverAdded.isEmpty()) {
            // Local added lines the server didn't see. Keep the base
            // plus local's additions in their original order — the
            // next push will upload them and the next pull will then
            // find the base in sync.
            return (kept + localAdded).joinToString("\n")
        }

        // Both sides added materially different lines: hard conflict.
        return null
    }

    /**
     * B1 — drop local rows whose soft-delete has been confirmed by the
     * server. The previous implementation piggy-backed on
     * `getDirtyNotes` and (because of the BUG 1 filter) returned
     * zero rows for already-synced tombstones, so acknowledged
     * deletes lived in the local table forever. The new
     * `getTombstonesAcknowledged` query returns the right set.
     */
    private suspend fun purgeAcknowledgedDeletes(userId: Int) {
        val toPurge = noteDao.getTombstonesAcknowledged(userId)
        for (row in toPurge) {
            noteDao.purgeNoteById(row.id)
            Logger.d(tag, "purged soft-deleted note localId=${row.id} serverId=${row.serverId}")
        }
    }

    private val pendingPushJob = AtomicReference<Job?>(null)

    /** Drain the local push queue. Public for the worker. */
    suspend fun pushLocalChanges(): Int = withContext(ioDispatcher) {
        val userId = currentUserId()
        if (userId <= 0) return@withContext 0
        syncMutex.withLock { pushLocalChangesInternal(userId) }
    }

    /**
     * V5.3 — debounced push (BUG 8 hardening). Bumps the persistent
     * pendingFlushVersion first; if the process dies before the
     * coroutine actually runs, the cold-start re-launches the push.
     *
     * B6 — pendingFlush is now bumped under the current userId.
     */
    fun pushLocalChangesDebounced() {
        val userId = currentUserId()
        if (userId <= 0) return
        SyncStore.bumpPendingFlush(context, userId)
        val newJob = pushScope.launch {
            delay(PUSH_DEBOUNCE_MS)
            syncMutex.withLock {
                pushLocalChangesInternal(userId)
            }
        }
        pendingPushJob.getAndSet(newJob)?.cancel()
    }

    /**
     * V5.3 — image-upload retry. When a push is filtered because a note
     * still embeds a local `file://` URI (i.e. the R2 upload is in flight
     * or failed), schedule a follow-up push so the rewritten URL is
     * eventually synced. Keeps `pendingFlush` version alive so
     * [coldStartFlushIfNeeded] also picks it up across process death.
     */
    private suspend fun schedulePendingImagePush(userId: Int) {
        val currentJob = coroutineContext[Job]
        SyncStore.bumpPendingFlush(context, userId)
        val newJob = pushScope.launch {
            delay(PUSH_DEBOUNCE_MS)
            syncMutex.withLock {
                pushLocalChangesInternal(userId)
            }
        }
        val prev = pendingPushJob.getAndSet(newJob)
        if (prev != null && prev !== currentJob) {
            prev.cancel()
        }
    }

    /** Flush the debounced push immediately, used by sync button + worker. */
    suspend fun flushPendingPush(): Int = withContext(ioDispatcher) {
        val userId = currentUserId()
        if (userId <= 0) return@withContext 0
        val currentJob = coroutineContext[Job]
        val prev = pendingPushJob.getAndSet(null)
        if (prev != null && prev !== currentJob) {
            prev.cancel()
        }
        syncMutex.withLock { pushLocalChangesInternal(userId) }
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
     *
     * B1 — the tombstone branch now uses [ApiClient.deleteNoteByServerId]
     * which distinguishes 200 (deleted) from 404 (already gone). Either
     * outcome purges the local row immediately, so we no longer wait
     * for a confirming pull. Any other error (network, 5xx) leaves the
     * row dirty for a retry on the next push.
     *
     * B3 — `serverId` is set locally at insert time, so the body
     * carries the same UUID the local row already holds. The legacy
     * `noteDao.updateNote(note.copy(serverId = response.id))` after
     * a successful upsert is gone.
     */
    private suspend fun pushLocalChangesInternal(userId: Int): Int {
        if (abortIfUserChanged(userId, "pushLocalChanges")) return 0
        if (!isOnline()) {
            Logger.d(tag, "pushLocalChanges: offline, skipping")
            return 0
        }

        val dirty = noteDao.getDirtyNotes(userId)
        if (dirty.isEmpty()) {
            SyncStore.consumePendingFlush(context, userId)
            return 0
        }

        val live = dirty.filter { it.deletedAt == null }
        val tombstones = dirty.filter { it.deletedAt != null }

        // F-05 — partition the live notes by whether their image is
        // "still in flight" (defer the whole push) or "gave up" (push
        // anyway so the TEXT is sent). See
        // [com.example.notes.util.ImageRetryStore] for the per-note
        // retry budget and the rationale.
        //
        // The 5-second retry loop used to spin forever when the
        // R2 upload kept failing — meaning the note's text never
        // reached the server either, since the deferral gate sits
        // IN FRONT of the push queue. The fix: after a note's
        // retry counter hits [ImageRetryStore.MAX_RETRIES], the
        // push pass treats it as "ready" and the text is sent.
        //
        // The wire copy is sanitized by [LocalImageUri.sanitizeForWire]
        // further down, so the `file://` URI never reaches the
        // server — it is replaced by a synthetic `pending-image:`
        // marker. The local row keeps the `file://` URI so the
        // editor can still render the image and re-upload it.
        // `dirtyRevision` is not touched.
        val deferred = mutableListOf<com.example.notes.data.local.NoteEntity>()
        val ready = mutableListOf<com.example.notes.data.local.NoteEntity>()
        for (note in live) {
            val hasLocalImage = hasLocalImageUri(note.content)
            if (!hasLocalImage) {
                ready.add(note)
                continue
            }
            if (com.example.notes.util.ImageRetryStore.noteGaveUpImage(
                    context, note.id)) {
                // Push the text with the image replaced by the
                // `pending-image:` marker. The server never learns
                // this device's filesystem path, other devices render
                // a placeholder, and the note's words are preserved.
                // The local image file stays on disk so the user can
                // still view it — and re-upload it — in this device's
                // editor.
                Logger.w(tag,
                    "pushLocalChanges: note=${note.id} hit image retry " +
                        "cap, pushing text with pending-image marker; " +
                        "the image itself is not on the server")
                ready.add(note)
            } else {
                deferred.add(note)
            }
        }

        if (ready.isEmpty() && tombstones.isEmpty()) {
            Logger.d(
                tag,
                "pushLocalChanges: ${deferred.size} note(s) deferred for image upload, no tombstones"
            )
            // Bump retry counters for every deferred note. The
            // function returns true for any note that just hit
            // MAX_RETRIES — we keep the loop in case a future
            // refactor wants per-note handling. For now, the
            // single-flight check on `schedulePendingImagePush`
            // decides whether to actually reschedule: if all
            // deferred notes gave up, the next call to
            // `pushLocalChangesInternal` will push them as-is.
            var allDeferredGaveUp = true
            for (note in deferred) {
                val gaveUp = com.example.notes.util.ImageRetryStore
                    .incrementAndCheck(context, note.id)
                if (gaveUp) {
                    Logger.w(tag,
                        "pushLocalChanges: note=${note.id} hit image " +
                            "retry cap on this iteration; will be " +
                            "pushed as-is on the next sync")
                } else {
                    allDeferredGaveUp = false
                }
            }
            if (deferred.isNotEmpty() && !allDeferredGaveUp) {
                schedulePendingImagePush(userId)
            } else {
                // Either no notes were deferred (shouldn't happen
                // given the outer if) or all of them just hit
                // MAX_RETRIES on this iteration. In the latter case
                // the next push pass will treat them as ready, so
                // we don't need to schedule a retry. Drop the
                // pending flush so we don't re-schedule on every
                // call to `coldStartFlushIfNeeded`.
                Logger.d(tag,
                    "pushLocalChanges: deferred notes all gave up " +
                        "or list is empty; consuming pending flush")
                SyncStore.consumePendingFlush(context, userId)
            }
            return 0
        }

        var pushed = 0
        for (note in ready) {
            // B3 — the local UUID is authoritative. The server's UPSERT
            // is a no-op when it sees the same id.
            //
            // F-05 — the wire copy of the content is sanitized: any
            // image block that still points at a LOCAL file
            // (`file://<filesDir>/images/img_X`) is replaced by a
            // synthetic `pending-image:` marker. The local row keeps
            // its `file://` URI (the editor needs it to render and
            // to re-upload), but the server — and therefore every
            // other device — never sees this device's filesystem
            // path. When the R2 upload finally succeeds the editor
            // rewrites the block to the public URL, the note is
            // saved (dirtyRevision bumps) and the next push ships
            // the real URL.
            val wireContent = com.example.notes.util.LocalImageUri
                .sanitizeForWire(note.content, context)
            val dto = NoteSyncDto(
                id = note.serverId ?: java.util.UUID.randomUUID().toString(),
                title = note.title,
                content = wireContent,
                is_favorite = if (note.isFavorite) 1 else 0,
                is_trashed = if (note.isTrashed) 1 else 0,
                background_color = note.backgroundColor,
                client_updated_at = note.updatedAt,
                created_at = note.createdAt
            )
            val result = ApiClient.upsertNote(dto)
            result.fold(
                onSuccess = { response ->
                    // B4 — baseTitle / baseContent come from the
                    // snapshot we just put on the wire, not the
                    // current row (which may have been edited while
                    // the request was in flight). The 3-way merge
                    // needs the *sent* content as the common
                    // ancestor so a subsequent pull can detect a
                    // genuine server-side divergence.
                    //
                    // F-05 — the ancestor is the SANITIZED content
                    // (what the server actually holds), not the
                    // local one (which still has the `file://`
                    // URI). Using the local content here would
                    // make every subsequent pull see a phantom
                    // divergence between the local row and the
                    // server's copy.
                    noteDao.markSynced(
                        localId = note.id,
                        syncedRev = note.dirtyRevision,
                        serverChangeId = response.change_id,
                        serverUpdatedAt = response.client_updated_at,
                        baseTitle = note.title,
                        baseContent = wireContent
                    )
                    // F-05 — drop the retry counter ONLY when the
                    // image actually made it to the server (i.e. the
                    // wire copy contains no `pending-image:` marker,
                    // which means the editor had already replaced
                    // the local `file://` URI with the public R2 URL).
                    //
                    // If the marker IS there, the image is still
                    // pending / failed — we must KEEP the counter at
                    // its cap so the next push of this note goes
                    // straight to the "push the text" path instead
                    // of burning another five deferral cycles (25 s)
                    // before the user's edit reaches the server.
                    if (!wireContent.contains(
                            com.example.notes.util.LocalImageUri.MARKER_PREFIX)) {
                        com.example.notes.util.ImageRetryStore.reset(context, note.id)
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
                // Local note never made it to the server. Drop it
                // immediately — there is nothing on the server side
                // to delete.
                noteDao.purgeNoteById(note.id)
                pushed++
                Logger.d(tag, "pushLocalChanges: tombstone without serverId, purged localId=${note.id}")
                continue
            }
            val result = ApiClient.deleteNoteByServerId(serverId)
            val outcome = result.getOrNull()
            when (outcome) {
                DeleteNoteOutcome.Deleted, DeleteNoteOutcome.NotFound -> {
                    // B1 — 200 (deleted) and 404 (already gone) are
                    // both terminal. The local row is dropped in the
                    // same pass, so a follow-up pull no longer needs
                    // to confirm anything.
                    noteDao.purgeNoteById(note.id)
                    pushed++
                    Logger.d(
                        tag,
                        "pushLocalChanges: tombstone $outcome for localId=${note.id} serverId=$serverId"
                    )
                }
                null -> {
                    // B1 — network / 5xx. Keep the row dirty; the
                    // next push will retry.
                    Logger.w(
                        tag,
                        "pushLocalChanges: server delete failed for localId=${note.id} serverId=$serverId"
                    )
                }
            }
        }

        SyncStore.consumePendingFlush(context, userId)
        return pushed
    }

    /**
     * V5.3 — BUG 8. Called once on cold start. If a previous process
     * bumped the pendingFlushVersion but was killed before draining it,
     * fire a push right away.
     *
     * B6 — the pending flush is read under the current userId. A
     * previous user that signed out will not have their queued
     * push accidentally drained against the new user's account.
     */
    fun coldStartFlushIfNeeded() {
        val userId = currentUserId()
        if (userId <= 0) return
        val pending = SyncStore.consumePendingFlush(context, userId)
        if (pending > 0L) {
            Logger.d(tag, "coldStartFlushIfNeeded: pending=$pending — scheduling flush")
            pushScope.launch {
                syncMutex.withLock { pushLocalChangesInternal(userId) }
            }
        }
    }

    suspend fun deleteOnServer(serverId: String): Boolean = withContext(ioDispatcher) {
        if (!isOnline()) {
            Logger.d(tag, "deleteOnServer: offline, skipping")
            return@withContext false
        }
        if (AuthStore.getActive(context) == null) {
            Logger.d(tag, "deleteOnServer: no session, skipping")
            return@withContext false
        }
        ApiClient.deleteNoteByServerId(serverId).fold(
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

    /**
     * F-03 — the pull loop's safety cap. The server itself is
     * bounded at [PULL_PAGE_LIMIT] per response, but a buggy server
     * (or a never-shrinking change log) could otherwise make us loop
     * forever. 100 pages × 500-row cap = 50,000 rows; a real Notes
     * user with even an aggressive blog-style workload rarely crosses
     * that in a single sync. When the cap is hit the persisted
     * `sync_cursor` lets the next pull pick up from where we stopped.
     */
    private const val PULL_MAX_PAGES = 100
    }
}
