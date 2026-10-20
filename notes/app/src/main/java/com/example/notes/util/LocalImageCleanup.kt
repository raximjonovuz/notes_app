package com.example.notes.util

import android.content.Context
import com.example.notes.data.local.NoteDao
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * D3 — one-time-per-day cleanup of orphaned `img_*` files in the
 * app's `filesDir`.
 *
 * Background: [com.example.notes.ui.edit.NoteEditViewModel.addImageUri]
 * copies every picked image into `filesDir` as `img_<UUID>.<ext>` so
 * the editor can hand a stable `file://` URI to the renderer (the
 * picker's `content://` grant expires on Activity restart). After the
 * image is uploaded to R2 and the editor's content is rewritten to
 * `https://…/files/…`, the local file is no longer needed.
 *
 * The user (a) removes the image from the note, (b) deletes the
 * whole note, (c) reinstalls the app, or (d) the R2 upload fails
 * permanently. In every case the local file is stranded and only
 * `LocalImageCleanup` reclaims it.
 *
 * Strategy:
 *   1. List every `img_*` file in `filesDir`.
 *   2. Collect every `file://` URI prefix the local repository
 *      references (any note whose `content` still embeds the URI).
 *   3. Delete files that are not referenced.
 *
 * The pass is O(N) on files + O(M) on notes; on a phone with a
 * couple of hundred notes the whole pass is < 50 ms. It is gated
 * to run at most once per 24 h via [LastCleanupStore] so the cost
 * doesn't compound across the 6 h background sync worker (which
 * also lives in `onCreate`).
 */
object LocalImageCleanup {

    private const val TAG = "LocalImageCleanup"

    /**
     * Run the cleanup if it has not been run in the last 24 h.
     * Idempotent; safe to call from `Application.onCreate` on every
     * cold start.
     *
     * @return the number of bytes freed (0 if skipped).
     */
    suspend fun runIfStale(context: Context, noteDao: NoteDao): Long {
        val now = System.currentTimeMillis()
        val last = LastCleanupStore.getLastRunMs(context)
        if (last > 0 && (now - last) < TimeUnit.HOURS.toMillis(24)) {
            Logger.d(TAG, "skipped — last run ${(now - last) / 60_000} min ago")
            return 0L
        }
        val freed = runNow(context, noteDao)
        LastCleanupStore.setLastRunMs(context, now)
        return freed
    }

    /**
     * Run the cleanup unconditionally. Exposed for the `adb shell`
     * debug hook (`am broadcast`) so support can re-trigger it
     * without waiting 24 h.
     *
     * F-28 — scans BOTH the new `filesDir/images/img_*` location (which
     * the editor writes to from V5.3.4 onwards) AND the legacy
     * `filesDir/img_*` location (in case [LocalImageMigration] was
     * interrupted between copy and delete). [collectFilePathsFromContent]
     * matches both URI prefixes for the same reason — a note still
     * pointing at a pre-migration path is still pinned.
     */
    suspend fun runNow(context: Context, noteDao: NoteDao): Long {
        val filesDir = context.filesDir
        val imagesDir = java.io.File(filesDir, "images")
        val newPrefix = "file://${filesDir.absolutePath}/images/"
        val legacyPrefix = "file://${filesDir.absolutePath}/"
        // Walk the entire tree under `filesDir` for any file whose
        // basename starts with `img_` — the new layout puts them in
        // the `images/` subfolder, the legacy layout puts them in the
        // root.
        val files = (filesDir.listFiles { f ->
            f.isFile && f.name.startsWith("img_")
        } ?: emptyArray())
        if (files.isEmpty()) {
            Logger.d(TAG, "no img_* files to scan")
            return 0L
        }
        // Pull the set of file:// URIs any note still references — both
        // the new and legacy prefixes are recognised so a half-finished
        // migration doesn't orphan files the editor still references.
        val filesDirPath = filesDir.absolutePath
        val referenced = HashSet<String>()
        for (note in noteDao.getAllLiveNotesSync()) {
            collectFilePathsFromContent(
                note.content, filesDirPath, referenced,
                newPrefix = newPrefix,
                legacyPrefix = legacyPrefix
            )
        }
        var freed = 0L
        var removed = 0
        for (f in files) {
            if (f.absolutePath in referenced) continue
            val size = f.length()
            if (f.delete()) {
                freed += size
                removed++
            } else {
                Logger.w(TAG, "could not delete orphan ${f.absolutePath}")
            }
        }
        // A separate stat counter helps spot leftover legacy files after
        // the migration completes.
        val legacyCount = files.count { it.parentFile == filesDir }
        val newCount = files.size - legacyCount
        Logger.d(
            TAG,
            "cleanup: scanned=${files.size} (new=$newCount legacy=$legacyCount) " +
                "removed=$removed freedBytes=$freed"
        )
        return freed
    }

    /**
     * Walk [content] looking for `file://<filesDirPath>/images/<filename>`
     * and `file://<filesDirPath>/<filename>` URIs. Anything that doesn't
     * match either prefix (e.g. an R2 `https://` URL or a `content://`
     * URI we never copied) is ignored. We only need to know which
     * on-disk files are still pinned by a note — the actual URI string
     * is just used to derive the absolute path.
     */
    private fun collectFilePathsFromContent(
        content: String,
        filesDirPath: String,
        out: MutableSet<String>,
        newPrefix: String,
        legacyPrefix: String
    ) {
        if (content.isEmpty()) return
        // Walk the content twice — once for each prefix. Doing it in a
        // single pass would require special-casing the `images/` slot
        // after `filesDir/`, which is harder to read and only marginally
        // faster.
        for (prefix in arrayOf(newPrefix, legacyPrefix)) {
            var from = 0
            while (true) {
                val idx = content.indexOf(prefix, from)
                if (idx < 0) break
                val pathStart = idx + prefix.length
                val pathEnd = content.indexOfAny(
                    charArrayOf('\n', ' ', '\r', '\t', '|', '"'),
                    pathStart
                ).let { if (it < 0) content.length else it }
                val name = content.substring(pathStart, pathEnd)
                if (name.isNotEmpty()) {
                    out += "$filesDirPath/$name"
                }
                from = pathEnd
            }
        }
    }
}
