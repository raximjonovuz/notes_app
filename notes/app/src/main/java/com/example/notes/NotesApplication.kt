package com.example.notes

import android.app.Application
import com.example.notes.data.local.AuthStore
import com.example.notes.data.local.NoteDatabase
import com.example.notes.data.remote.NotesSyncRepository
import com.example.notes.data.repository.NoteRepository
import com.example.notes.work.BackgroundSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NotesApplication : Application() {

    val database: NoteDatabase by lazy {
        NoteDatabase.getDatabase(this)
    }

    val repository: NoteRepository by lazy {
        NoteRepository(database.noteDao(), this)
    }

    /** Convenience accessor for the sync coordinator. */
    val syncRepository: NotesSyncRepository by lazy {
        NotesSyncRepository(noteDao = database.noteDao(), context = this)
    }

    /**
     * V4.3: application-scoped CoroutineScope used by editor save flows
     * that must complete even if the user immediately navigates away
     * (which would otherwise cancel the NoteEditViewModel.viewModelScope).
     * Tied to the Application lifecycle so it lives for the whole process.
     */
    val applicationScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * F-04 + F-14 — drives background sync from the network /
     * lifecycle events that should kick the data layer. See
     * [com.example.notes.util.SyncTrigger] for the design rationale
     * (debounce, single-flight guard, callbackFlow unregister).
     */
    private val syncTrigger: com.example.notes.util.SyncTrigger by lazy {
        com.example.notes.util.SyncTrigger(
            applicationScope = applicationScope,
            syncRunner = object : com.example.notes.util.SyncTrigger.SyncRunner {
                override suspend fun run() = syncRepository.syncIfOnline()
            }
        )
    }

    override fun onCreate() {
        super.onCreate()
        // V5.1: load the persisted dark-mode preference BEFORE any
        // composable runs so the splash / first screen already reflects
        // the user's choice. Without this the theme would always start
        // as light, even if the user toggled dark on a previous run.
        com.example.notes.ui.theme.ThemeManager.loadPersisted(this)

        // Restore the bearer token if the user is already logged in so the
        // first sync attempt after a cold start is authenticated without
        // requiring an extra login round-trip.
        AuthStore.getActive(this)
        // Push the (possibly restored) user id into NoteRepository so its
        // Flow sources immediately scope to the right account.
        repository.refreshActiveUser()

        // V5.0: hand the SyncRepository to the background sync worker
        // (no DI yet — Worker can't constructor-inject) and enqueue the
        // periodic job. Idempotent — KEEP policy means re-enqueueing is
        // a no-op if it's already scheduled.
        com.example.notes.work.SyncRepositoryLocator.set(syncRepository)
        com.example.notes.work.BackgroundSyncWorker.schedule(this)

        // V5.3 (BUG 8) — if the previous process queued a push but
        // was killed before draining it, fire one now. The version is
        // reset to 0 inside `coldStartFlushIfNeeded` regardless of
        // outcome so we don't loop on a no-op.
        syncRepository.coldStartFlushIfNeeded()

        // D3 / F-28 — first migrate any legacy `filesDir/img_*` files
        // (and the matching `file://` URIs inside note content) into
        // the new `filesDir/images/` layout, THEN run the orphan-image
        // cleanup. Running the two on the same coroutine means they
        // cannot interleave on the IO dispatcher: the cleanup won't
        // ever see the half-migrated state where a new `images/img_X`
        // exists on disk but the DB still points at `filesDir/img_X`.
        //
        // The splash gate ([LocalImageMigration.completionSignal])
        // is released from inside [runIfNeeded] via its own
        // try/finally; the outer `finally` here is a defensive
        // backstop so a future regression that lets an exception
        // escape [runIfNeeded] BEFORE its own `finally` line still
        // can't leave the splash gate stuck. Calling
        // [CompletableDeferred.complete] twice is a documented no-op.
        applicationScope.launch(Dispatchers.IO) {
            try {
                com.example.notes.util.LocalImageMigration.runIfNeeded(
                    this@NotesApplication,
                    database.noteDao()
                )
            } finally {
                com.example.notes.util.LocalImageMigration.completionSignal
                    .complete(Unit)
            }
            // D3 — reclaim orphaned image files. Gated to once per 24 h
            // by [com.example.notes.util.LastCleanupStore] so the cost
            // is bounded across the 6 h background sync tick.
            com.example.notes.util.LocalImageCleanup.runIfStale(
                this@NotesApplication,
                database.noteDao()
            )
        }

        // F-04 + F-14 — start the sync trigger. From this point
        // onwards the data layer responds to network restoration
        // (debounced 3 s) and to manual [requestSync] calls. The
        // cold-start sync is fired below; the network callback
        // itself fires only when connectivity actually changes.
        syncTrigger.start(this)
        syncTrigger.requestSync(
            com.example.notes.util.SyncTrigger.TriggerReason.ColdStart
        )
    }

    /**
     * F-14 — call this from [com.example.notes.ui.auth.AuthViewModel]
     * after a successful Google login so the freshly-acquired token
     * gets to push the user's first sync without waiting for the
     * 6 h periodic worker (or a network flap).
     */
    fun onLoginSucceeded() {
        syncTrigger.requestSync(
            com.example.notes.util.SyncTrigger.TriggerReason.LoginSuccess
        )
    }
}