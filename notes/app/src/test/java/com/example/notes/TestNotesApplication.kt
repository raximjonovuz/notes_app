package com.example.notes

import android.app.Application
import com.example.notes.data.local.NoteDatabase

/**
 * V5.0.2 — Robolectric-friendly Application. Skips WorkManager
 * initialization (which would otherwise throw
 * `WorkManagerImpl` initialization errors under the unit-test
 * classpath) and skips the AuthStore auto-login that pulls
 * persisted state from the device.
 */
class TestNotesApplication : Application() {
    val database: NoteDatabase by lazy { NoteDatabase.getDatabase(this) }
}
