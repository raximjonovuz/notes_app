package com.example.notes.util

import android.content.Context

/**
 * Tiny shared-preferences-backed timestamp store for one-shot
 * periodic cleanups (currently [LocalImageCleanup]). The previous
 * implementation inlined the read/write in the cleanup class; this
 * keeps the helper reusable for future "run once a day" hooks
 * (cache prewarm, WAL checkpoint, etc.).
 */
object LastCleanupStore {
    private const val PREFS = "notes_cleanup"
    private const val KEY_LOCAL_IMAGES = "local_images_last_run_ms"

    fun getLastRunMs(context: Context): Long =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LOCAL_IMAGES, 0L)

    fun setLastRunMs(context: Context, ms: Long) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LOCAL_IMAGES, ms)
            .apply()
    }
}
