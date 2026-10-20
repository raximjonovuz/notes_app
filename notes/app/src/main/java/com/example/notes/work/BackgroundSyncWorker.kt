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
 * B2 — the worker no longer swallows the sync result. A network blip
 * surfaces as [Result.retry] (WorkManager will back off and try again)
 * while a 401 (session expired) returns [Result.success] so the
 * periodic schedule isn't burned retrying every 6 hours forever; the
 * UI will pick up the cleared active session the next time the user
 * opens the app and route them back to the Login screen.
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
            val outcome = repo.syncIfOnline()
            when (outcome) {
                is NotesSyncRepository.SyncOutcome.Success -> {
                    Logger.d("BackgroundSyncWorker", "doWork: success")
                    Result.success()
                }
                is NotesSyncRepository.SyncOutcome.Skipped -> {
                    Logger.d("BackgroundSyncWorker", "doWork: skipped (offline or no session)")
                    // Skipped is not a failure; the next period will try
                    // again. Returning success keeps WorkManager from
                    // applying a backoff.
                    Result.success()
                }
                is NotesSyncRepository.SyncOutcome.Unauthorized -> {
                    Logger.w("BackgroundSyncWorker", "doWork: 401 — session cleared, no retry")
                    // Don't retry: the session is gone, retrying will just
                    // 401 again. The UI will route the user to Login.
                    Result.success()
                }
                is NotesSyncRepository.SyncOutcome.Failed -> {
                    Logger.w(
                        "BackgroundSyncWorker",
                        "doWork: transport failure (${outcome.reason}) — retrying"
                    )
                    // Network or 5xx — let WorkManager apply its default
                    // exponential backoff and try again on the next
                    // period. The previous behaviour swallowed the error
                    // and reported success, which masked real outages.
                    Result.retry()
                }
            }
        } catch (t: Throwable) {
            Logger.w("BackgroundSyncWorker", "doWork threw", t)
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
                // B2 — was 1 day; bumped to 6 hours. The previous
                // schedule was so long that a user who took their
                // device offline for the weekend and came back online
                // would see up to 24 h of stale notes before the
                // background pass ran. 6 h is the same cadence as the
                // Flex window WorkManager already uses internally.
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