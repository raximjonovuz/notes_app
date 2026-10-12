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
    }
}