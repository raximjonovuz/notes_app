package com.example.notes.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.notes.data.local.AuthStore
import com.example.notes.data.local.NoteDao
import com.example.notes.data.local.NoteEntity
import com.example.notes.data.local.SyncStore
import com.example.notes.data.remote.NoteSyncDto
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
 * Coordinates bidirectional sync between the local Room database and the
 * remote notes backend (V3.1).
 *
 * For V3.1 we keep this intentionally simple — **pull only**. When the user
 * taps "Sync" (or a sync is triggered by some other lifecycle event), we:
 *
 *   1. Bail out immediately if the device is offline or no session is
 *      stored locally (no point talking to /notes without a token).
 *   2. Ask the server for notes updated after the last successful sync
 *      timestamp (if we have one).
 *   3. For each returned note, merge it into the local DB. If we already
 *      have a local row with the same `serverId` we update in place;
 *      otherwise we insert a fresh row.
 *   4. Persist the new sync timestamp so the next pull is incremental.
 *
 * Push (uploading local-only notes) is left as a TODO: it requires tracking
 * per-note "dirty" flags so we don't push notes that have not actually
 * changed since the last push. For now we assume single-device use.
 */
class NotesSyncRepository(
    private val noteDao: NoteDao,
    private val context: Context
) {

    private val activeUserId: Int
        get() = AuthStore.getActive(context)?.userId ?: 0

    private val tag = "NotesSyncRepository"

    /**
     * Attempts to pull the latest changes from the backend and merge them
     * into the local DB. Returns `true` if a sync round-trip completed
     * successfully, `false` otherwise (no network, no session, server
     * failure, …). The caller can surface a snackbar or status text based
     * on this boolean.
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

        val lastSync = SyncStore.getLastSync(context)
        val response = ApiClient.listNotes(lastSync).getOrElse {
            Logger.w(tag, "syncIfOnline: listNotes failed", it)
            return@withContext false
        }

        try {
            for (note in response.notes) {
                // V5.0.2 — three-way merge that never inserts duplicates:
                //   1. serverId match → update in place
                //   2. (title, content, userId) match on a LOCAL NOTE WITH
                //      NULL serverId → the user created this note locally
                //      and the push that should have linked it failed (or
                //      was still in flight). Stamp the server UUID onto
                //      that local copy instead of creating a duplicate.
                //      Importantly we only backfill when local.serverId IS
                //      NULL — touching an already-linked row would
                //      ping-pong between two server copies of the same
                //      content and is the #1 source of the "duplicate
                //      note after sync" bug.
                //   3. no match at all → brand new row, safe to insert.
                //   4. match by content AND local.serverId IS NOT NULL
                //      → the local note is already linked to a different
                //      server copy (orphan from a prior failed push, or
                //      just the user's latest edit). Skip — overwriting
                //      the existing link would create the duplicate we
                //      are trying to prevent.
                val byServerId = noteDao.getNoteByServerId(note.id)
                if (byServerId != null) {
                    noteDao.updateNote(
                        byServerId.copy(
                            title = note.title,
                            content = note.content,
                            isFavorite = note.is_favorite != 0,
                            isTrashed = note.is_trashed != 0,
                            backgroundColor = note.background_color,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    Logger.d(tag, "syncIfOnline: updated existing serverId=${note.id}")
                } else {
                    val byContent = noteDao.findByContentAndUser(
                        title = note.title,
                        content = note.content,
                        userId = activeUserId
                    )
                    when {
                        byContent == null -> {
                            noteDao.insertNote(note.toEntity(userId = activeUserId))
                            Logger.d(tag, "syncIfOnline: inserted new serverId=${note.id}")
                        }
                        byContent.serverId == null -> {
                            noteDao.updateNote(
                                byContent.copy(
                                    serverId = note.id,
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                            Logger.d(
                                tag,
                                "syncIfOnline: backfilled serverId on unsynced note (localId=${byContent.id})"
                            )
                        }
                        else -> {
                            // Local note is already linked to a different
                            // server copy. Don't overwrite the link — the
                            // next push will reconcile the two via UPSERT
                            // and the user keeps their edits.
                            Logger.d(
                                tag,
                                "syncIfOnline: local note already linked to ${byContent.serverId}, skipping server copy ${note.id}"
                            )
                        }
                    }
                }
            }
            SyncStore.setLastSync(context, response.sync_timestamp)
            true
        } catch (t: Throwable) {
            Logger.w(tag, "syncIfOnline: DB merge failed", t)
            false
        }
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * V5.0.2 — debounced push coalescing. Auto-save currently fires every
     * ~300ms while the user types, which means [pushLocalChanges] was
     * hitting the network dozens of times per second. This wrapper cancels
     * the in-flight one and schedules a fresh push [PUSH_DEBOUNCE_MS] from
     * now — by the time the debounce window expires, the user is usually
     * done typing and we send a single round-trip with the final state.
     *
     * Fire-and-forget; the caller does NOT suspend on the network.
     */
    private val pushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingPushJob: Job? = null
    private val pushMutex = Mutex()

    /**
     * V5.0.2 — push every locally-created note (serverId == null) for the
     * active user to the backend. After a successful upsert, the returned
     * server-assigned UUID is written back to the local row so subsequent
     * syncs treat it as already-synced.
     *
     * Returns the number of notes that were successfully pushed. Delegates
     * to [pushLocalChangesInternal] so the debounced / forced variants
     * share the same implementation.
     */
    suspend fun pushLocalChanges(): Int = withContext(Dispatchers.IO) {
        pushMutex.withLock { pushLocalChangesInternal() }
    }

    /**
     * V5.0.2 — schedule a push that fires after [PUSH_DEBOUNCE_MS] of
     * inactivity. Each call cancels the previous pending push so we only
     * run once for a burst of edits. Use this from places where the user
     * has just made a change (editor saves, list toggles).
     */
    fun pushLocalChangesDebounced() {
        synchronized(this) {
            pendingPushJob?.cancel()
            pendingPushJob = pushScope.launch {
                delay(PUSH_DEBOUNCE_MS)
                pushMutex.withLock {
                    pushLocalChangesInternal()
                }
            }
        }
    }

    /**
     * V5.0.2 — flush any pending debounced push AND run the push
     * immediately. Use this when the user explicitly asks for a sync
     * (Sync button, editor close, etc.) so they don't wait for the
     * debounce window.
     */
    suspend fun flushPendingPush(): Int = withContext(Dispatchers.IO) {
        synchronized(this@NotesSyncRepository) {
            pendingPushJob?.cancel()
            pendingPushJob = null
        }
        pushMutex.withLock {
            pushLocalChangesInternal()
        }
    }

    /**
     * Internal implementation that always pushes (no debouncing). All
     * public callers go through [pushLocalChanges], [pushLocalChangesDebounced]
     * or [flushPendingPush] which all funnel through here.
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
        val pending = noteDao.getNotesWithoutServerId(activeUserId)
        if (pending.isEmpty()) return 0

        var pushed = 0
        for (note in pending) {
            val dto = NoteSyncDto(
                id = note.serverId ?: java.util.UUID.randomUUID().toString(),
                title = note.title,
                content = note.content,
                is_favorite = if (note.isFavorite) 1 else 0,
                is_trashed = if (note.isTrashed) 1 else 0,
                background_color = note.backgroundColor
            )
            val result = ApiClient.upsertNote(dto)
            result.fold(
                onSuccess = { response ->
                    noteDao.updateNote(
                        note.copy(
                            serverId = response.id,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    pushed++
                    Logger.d(tag, "pushLocalChanges: pushed note ${note.id} -> ${response.id}")
                },
                onFailure = { e ->
                    Logger.w(tag, "pushLocalChanges: upsertNote failed for ${note.id}", e)
                }
            )
        }
        return pushed
    }

    companion object {
        /**
         * Debounce window for [pushLocalChangesDebounced]. Long enough
         * that a typing burst (typical 3-5 chars/sec) collapses into a
         * single push, short enough that the user perceives sync as
         * near-immediate after they stop typing.
         */
        private const val PUSH_DEBOUNCE_MS = 5_000L
    }

    /**
     * V4.4 — hard-delete a note on the server. Returns true on success,
     * false if the request failed (offline, server error, …). The local
     * caller decides what to do with a false return.
     */
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

    /** Map a server-side note DTO into a freshly-inserted local entity. */
    private fun NoteSyncDto.toEntity(userId: Int): NoteEntity {
        val now = System.currentTimeMillis()
        return NoteEntity(
            id = 0,
            title = title,
            content = content,
            createdAt = now,
            updatedAt = now,
            isFavorite = is_favorite != 0,
            isTrashed = is_trashed != 0,
            backgroundColor = background_color,
            fontWeight = 0,
            textSize = 0,
            imageUris = "",
            serverId = id,
            userId = userId
        )
    }
}