package com.example.notes.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.notes.data.remote.NotesSyncRepository
import com.example.notes.util.Logger
import java.util.concurrent.TimeUnit

/**
 * V5.0 — periodic background sync. Once a day (WorkManager's minimum
 * interval) we attempt to push any local-only notes and pull server
 * updates. The user can still tap the Sync button for an immediate
 * round-trip — this worker is a safety net for the absent-minded user.
 *
 * Network constraint is [NetworkType.CONNECTED] so the worker doesn't
 * burn battery trying to sync over Wi-Fi-only on a mobile-only device.
 */
class BackgroundSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repo = SyncRepositoryLocator.get() ?: return Result.success()
        return try {
            // V5.0.2: this worker is the periodic safety net — it always
            // pushes (no debounce), then pulls. flushPendingPush is the
            // right entry point here so any pending debounced push from
            // the editor is also flushed.
            val pushed = repo.flushPendingPush()
            val pulled = repo.syncIfOnline()
            Logger.d(
                "BackgroundSyncWorker",
                "doWork: pushed=$pushed pulled=$pulled"
            )
            Result.success()
        } catch (t: Throwable) {
            Logger.w("BackgroundSyncWorker", "doWork failed", t)
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_NAME = "notes.background-sync"

        /**
         * Schedule the periodic worker. Idempotent — calling multiple
         * times does not enqueue duplicate work.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<BackgroundSyncWorker>(
                1, TimeUnit.DAYS,
                // Flex window: WorkManager can run the job anywhere within
                // this window after the period. The flex helps the OS
                // batch device wake-ups across apps.
                6, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}

/**
 * V5.0 — minimal service-locator so the worker can pick up the
 * already-constructed [NotesSyncRepository] from the [com.example.notes.NotesApplication]
 * without dragging Hilt into the worker layer (Worker construction is
 * done by WorkManager, not by us, so constructor injection doesn't work).
 *
 * Set once during application onCreate, read by every worker tick.
 */
object SyncRepositoryLocator {
    @Volatile
    private var instance: NotesSyncRepository? = null

    fun get(): NotesSyncRepository? = instance
    fun set(repo: NotesSyncRepository) {
        instance = repo
    }
}