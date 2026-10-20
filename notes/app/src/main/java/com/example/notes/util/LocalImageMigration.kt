package com.example.notes.util

import android.content.Context
import com.example.notes.data.local.NoteDao
import com.example.notes.ui.edit.ContentBlock
import com.example.notes.ui.edit.parseBlocksFromContent
import com.example.notes.ui.edit.serializeBlocksToContent
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * F-28 — one-time, idempotent migration that moves every locally
 * captured image from `Context.getFilesDir()/img_<UUID>.<ext>`
 * (the V5.0–V5.3.3 layout) into
 * `Context.getFilesDir()/images/img_<UUID>.<ext>` (the V5.3.4+
 * layout). The reason this exists at all is that the Android Auto
 * Backup XML rule `path="img_*"` does not actually support `*` as a
 * wildcard (per the official Android docs, only literal names and
 * subdirectory paths are matched), so the previous fix was a no-op
 * on real devices. Excluding the whole `images/` directory works
 * because the `domain="file"` rule does cover subdirectories.
 *
 * Migration order:
 *  1. (a) Copy every `filesDir/img_*` file to `filesDir/images/img_*`.
 *     Idempotent: a `copyTo(overwrite = false)` skips files that
 *     already exist, so an interrupted run can safely resume.
 *  2. (b) Read every note's `content`, parse it via the shared
 *     [parseBlocksFromContent] (so the V2.6 header and the JSON v2
 *     envelope are both handled), rewrite every `ImageBlock.uri`
 *     with the old prefix to one with the new prefix, and persist
 *     via [NoteDao.updateContentOnly] — which does **not** bump
 *     `dirtyRevision` (the migration is purely a local-path fixup;
 *     the sync layer must not see this as a user-driven edit).
 *  3. (c) For each legacy file, only delete it if **no live note's
 *     content still references the legacy URI** for that file. This
 *     is a per-file check (not a single "all notes done" gate) so a
 *     partial step (b) — e.g. a single DAO update that threw and was
 *     logged — does not leave a note pointing at a deleted file.
 *  4. (d) Set the "completed" flag only when the migration finished
 *     without any per-step errors. If anything went wrong the flag
 *     stays false and the next cold start re-runs the migration
 *     (every step is independently idempotent so a retry is safe).
 *
 * Splash-screen gate
 * ------------------
 * The [completionSignal] is a [CompletableDeferred] that resolves
 * the moment the migration finishes — successfully OR with errors.
 * The splash screen awaits it before navigating away, so the user
 * never sees a note whose `file://` URI is mid-migration. On error
 * the deferred is still resolved normally (so the UI doesn't block
 * forever) and the failure is logged; the next cold start will
 * retry the migration because the "completed" flag was not set.
 */
object LocalImageMigration {

    private const val TAG = "LocalImageMigration"

    private const val PREFS_NAME = "image_migration"
    private const val KEY_DONE = "completed_v1"

    /**
     * Resolved exactly once, when [runIfNeeded] finishes — success
     * or failure. The splash screen awaits this so the UI never
     * opens while the migration is still rewriting note content.
     *
     * The signal is guaranteed to be resolved on every exit path
     * out of [runIfNeeded] (early return, normal exit, exception,
     * coroutine cancellation) by the `try/finally` in that
     * function and the outer `try/finally` in [NotesApplication].
     * Idempotent: calling [CompletableDeferred.complete] on an
     * already-completed deferred is a documented no-op.
     *
     * Exposed for the splash screen only; tests should drive
     * [runIfNeeded] directly and use [resetCompletionSignalForTesting]
     * to clear the gate between cases.
     */
    private var _completionSignal: CompletableDeferred<Unit> = CompletableDeferred()
    val completionSignal: CompletableDeferred<Unit> get() = _completionSignal

    /**
     * Test-only — clear the splash gate so the next call to
     * [runIfNeeded] has to complete it again. Production code never
     * calls this; it's here so the unit tests can verify "the
     * migration actually resolved the signal" rather than just
     * "the signal is resolved (because a prior test completed it)".
     */
    internal fun resetCompletionSignalForTesting() {
        _completionSignal = CompletableDeferred()
    }

    /**
     * Public entry point. Returns `true` if any work was performed
     * (including "started, hit a recoverable error, retry next
     * time"), `false` only when the migration had already completed
     * on a prior run.
     *
     * Always runs on [Dispatchers.IO] — file copies and DB writes
     * both block.
     *
     * The splash gate is released in a `try/finally` that wraps the
     * entire `withContext` call (not the block inside it). This is
     * deliberate: if the `try/finally` lived inside the `withContext`
     * block, the `finally` would NOT run when `withContext` itself
     * throws — which is exactly what happens on coroutine
     * cancellation, before the block body has a chance to start.
     * Hoisting the `try/finally` one level out ensures the gate is
     * released on **every** exit path: early return, normal
     * completion, any thrown Throwable (including
     * [CancellationException], which is re-thrown so the coroutine
     * cancellation contract is preserved).
     */
    suspend fun runIfNeeded(context: Context, noteDao: NoteDao): Boolean =
        try {
            withContext(Dispatchers.IO) {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                if (prefs.getBoolean(KEY_DONE, false)) {
                    Logger.d(TAG, "runIfNeeded: already completed, skipping")
                    return@withContext false
                }

                val hadError = try {
                    runMigrationSteps(context, noteDao, prefs)
                } catch (t: CancellationException) {
                    // The coroutine was cancelled (e.g. process death).
                    // Propagate so the cancellation contract is honored.
                    // The outer `try/finally` still resolves the splash
                    // gate before the cancellation unwinds the call.
                    throw t
                } catch (t: Throwable) {
                    Logger.e(TAG, "migration crashed unexpectedly", t)
                    true
                }

                if (!hadError) {
                    prefs.edit().putBoolean(KEY_DONE, true).apply()
                    Logger.d(TAG, "runIfNeeded: completed")
                    true
                } else {
                    Logger.w(TAG,
                        "runIfNeeded: had errors, completed_v1 NOT set; " +
                            "next start will retry")
                    false
                }
            }
        } finally {
            // ALWAYS resolve the splash gate, on every exit path:
            //   - early return (completed_v1 already true),
            //   - normal completion (hadError == false),
            //   - any thrown Throwable from the migration body,
            //   - CancellationException from withContext.
            // `complete(Unit)` is idempotent — calling it on an
            // already-completed deferred is a no-op.
            completionSignal.complete(Unit)
        }

    /**
     * Run steps (a), (b) and (c) in order. Each step has its own
     * per-item try/catch so a single failed file or a single failed
     * DB update doesn't poison the whole migration. Returns `true`
     * if at least one step reported any failure.
     */
    private suspend fun runMigrationSteps(
        context: Context,
        noteDao: NoteDao,
        @Suppress("UNUSED_PARAMETER") prefs: android.content.SharedPreferences
    ): Boolean {
        val filesDir = context.filesDir
        val imagesDir = File(filesDir, "images").apply {
            if (!exists() && !mkdirs()) {
                Logger.w(TAG, "could not create $absolutePath; aborting")
                return true
            }
        }
        val filesDirPath = filesDir.absolutePath
        val newPrefix = "file://$filesDirPath/images/"
        val legacyPrefix = "file://$filesDirPath/"

        // ---- Step (a): copy legacy files into images/ ----
        val legacyFiles = filesDir.listFiles { f ->
            f.isFile && f.name.startsWith("img_")
        } ?: emptyArray()
        val copyFailed = stepCopy(legacyFiles, imagesDir)

        // ---- Step (b): rewrite note content ----
        // We touch every live (non-soft-deleted) note. Tombstoned
        // notes (deletedAt != null) are skipped by the DAO query
        // because they no longer have a rendered image; their
        // local image file, if any, is reclaimed by
        // [LocalImageCleanup] the next time it runs.
        val notes = noteDao.getAllLiveNotesSync()
        val rewriteFailed = stepRewrite(notes, noteDao, legacyPrefix, newPrefix)

        // ---- Step (c): per-file deletion check ----
        // Only delete a legacy file if **no live note** still
        // references its legacy URI. This is a per-file gate (not
        // a global "all notes done" flag) because if a single
        // step (b) update failed for one note, that note still carries
        // the legacy URI — the corresponding file must stay.
        val deleteFailed = stepDelete(
            legacyFiles, filesDirPath, legacyPrefix, noteDao
        )

        return copyFailed > 0 || rewriteFailed > 0 || deleteFailed > 0
    }

    /** Step (a) — copy each legacy `img_*` into `images/`. Returns the number of failures. */
    private fun stepCopy(
        legacyFiles: Array<File>,
        imagesDir: File
    ): Int {
        var copied = 0
        var failed = 0
        for (legacy in legacyFiles) {
            val dest = File(imagesDir, legacy.name)
            if (dest.exists()) continue
            try {
                legacy.copyTo(dest, overwrite = false)
                copied++
            } catch (t: Throwable) {
                failed++
                Logger.w(TAG, "copy failed for ${legacy.name}: " +
                    "${t.message ?: t.javaClass.simpleName}", t)
            }
        }
        Logger.d(TAG, "step (a): copied=$copied failed=$failed (legacy=${legacyFiles.size})")
        return failed
    }

    /**
     * Step (b) — rewrite every live note's content so its
     * `ImageBlock.uri` no longer points at the legacy path.
     * Per-note try/catch: a single DAO update that throws is logged
     * and skipped; the rest still go through. The migration is
     * still re-runnable on the next cold start (the failed note's
     * legacy URI is still there for the next pass to find).
     */
    private suspend fun stepRewrite(
        notes: List<com.example.notes.data.local.NoteEntity>,
        noteDao: NoteDao,
        legacyPrefix: String,
        newPrefix: String
    ): Int {
        var rewritten = 0
        var skippedUnchanged = 0
        var failed = 0
        for (note in notes) {
            val newContent = try {
                rewriteContent(note.content, legacyPrefix, newPrefix)
            } catch (t: Throwable) {
                failed++
                Logger.w(TAG, "rewrite parse failed for note id=${note.id}: " +
                    "${t.message ?: t.javaClass.simpleName}", t)
                null
            } ?: continue
            if (newContent == note.content) {
                skippedUnchanged++
                continue
            }
            try {
                noteDao.updateContentOnly(note.id, newContent)
                rewritten++
            } catch (t: Throwable) {
                failed++
                Logger.w(TAG, "updateContentOnly failed for note id=${note.id}: " +
                    "${t.message ?: t.javaClass.simpleName}", t)
            }
        }
        Logger.d(TAG, "step (b): rewritten=$rewritten " +
            "unchanged=$skippedUnchanged failed=$failed (notes=${notes.size})")
        return failed
    }

    /**
     * Step (c) — for each legacy file, only delete it if no live
     * note still references its legacy URI. The check is per-file
     * (not a global "all notes done" gate) because if a single
     * step (b) update failed for one note, that note still carries
     * the legacy URI — the corresponding file must stay.
     *
     * Re-reads the live notes from the DAO rather than using the
     * pre-migration snapshot: between step (b) and step (c) some
     * notes' `content` may have been rewritten and some not, and the
     * per-file gate has to make its decision on the current DB
     * state, not the state at the start of the migration.
     */
    private suspend fun stepDelete(
        legacyFiles: Array<File>,
        filesDirPath: String,
        legacyPrefix: String,
        noteDao: NoteDao
    ): Int {
        // Per-file decision: collect every absolute legacy path the
        // surviving note content still references, then only delete
        // a file if its absolute path is not in that set.
        val stillReferenced = HashSet<String>()
        for (note in noteDao.getAllLiveNotesSync()) {
            collectLegacyFilePaths(note.content, filesDirPath, legacyPrefix,
                stillReferenced)
        }
        val (deleted, failed, kept) = deleteLegacyFilesStep(
            legacyFiles, stillReferenced
        )
        Logger.d(TAG, "step (c): deleted=$deleted failed=$failed " +
            "keptByReference=$kept")
        return failed
    }

    /**
     * Walk [content] for legacy `file://<filesDirPath>/<name>` URIs
     * and add each `<filesDirPath>/<name>` to [out]. Excludes the
     * new `…/images/…` form so a successfully-rewritten URI is
     * never counted against the deletion gate.
     *
     * The set entries are produced via [File.absolutePath] so the
     * path separator is always the platform's. The legacy prefix
     * that we look up in the content uses the same `/` separator
     * that `Uri.fromFile` writes; the basename is whatever follows
     * in the content. Reconstructing via [File] on `filesDirPath`
     * is the only portable way to compare against the on-disk
     * file's `absolutePath` later in [deleteLegacyFilesStep].
     *
     * The terminator set below is the **complete** list of byte
     * values that the walker treats as "the URI ends here". It is
     * derived from the two on-disk formats this codebase can
     * produce (the migration is idempotent across both):
     *
     *   - V2.6 legacy header (`ContentBlock.kt`):
     *       `…\n|||IMG:<uri>|||\n…`
     *     The URI is wrapped between `|||IMG:` and `|||`. Any of `|`,
     *     `\n` (next block, end of content, or JSON escape) is a
     *     boundary.
     *
     *   - JSON v2 envelope (`ContentBlock.kt`):
     *       `…,"uri":"<uri>",…`
     *     The URI is wrapped between the JSON quotes. The closing
     *     `"` is a boundary; an unescaped `"` inside the URI is not
     *     legal in either of our serializers and would already have
     *     failed the [serializeBlocksToContent] round-trip, so the
     *     terminator being the next quote is sufficient.
     *
     *   - Common defensive separators:
     *       ` ` (space) and `\t` (tab) — the URI itself cannot
     *       legally contain them, but a future feature that
     *       concatenates the URI inside a larger text blob (e.g.
     *       inline autocomplete suggestion, an OCR-imported note)
     *       could produce a surrounding word boundary.
     *       `\r` — Windows line ending (CR before LF, both may
     *       appear if a note was imported from a CRLF source).
     *
     * The walker does NOT terminate on `<`, `>`, `?`, `#`, `&`, or
     * `'`: none of those bytes can legally appear between the
     * opening of a URI and one of the four format delimiters
     * above, so adding them would either be dead code (false
     * positives) or break a future format that happens to quote
     * the URI with `<…>` (which we don't have today).
     *
     * If a future feature introduces a new format (e.g. a Markdown
     * image with title `![alt](file://…)`), add the new delimiters
     * here AND add a round-trip test that pins the walker.
     */
    internal val LEGACY_PATH_TERMINATORS = charArrayOf(
        '\n',   // V2.6 separator / JSON escape
        ' ',    // defensive: word boundary
        '\r',   // defensive: CRLF
        '\t',   // defensive: tab-separated
        '|',    // V2.6 `|||` boundary
        '"'     // JSON v2 string boundary
    )

    internal fun collectLegacyFilePaths(
        content: String,
        filesDirPath: String,
        legacyPrefix: String,
        out: MutableSet<String>
    ) {
        if (content.isEmpty()) return
        var from = 0
        while (true) {
            val idx = content.indexOf(legacyPrefix, from)
            if (idx < 0) break
            val pathStart = idx + legacyPrefix.length
            val pathEnd = content.indexOfAny(
                LEGACY_PATH_TERMINATORS, pathStart
            ).let { if (it < 0) content.length else it }
            // The legacy prefix ends in `/`; the substring after it
            // normally starts with the file basename, possibly with a
            // leading `/` from `Uri.fromFile` collapsing the two
            // slashes. Strip that.
            val name = content.substring(pathStart, pathEnd).trimStart('/')
            if (name.isEmpty() || name.startsWith("images/")) {
                from = pathEnd
                continue
            }
            out += File(filesDirPath, name).absolutePath
            from = pathEnd
        }
    }

    /**
     * Delete every legacy file in [legacyFiles] whose absolute path
     * is not present in [stillReferenced]. Returns a triple
     * (deleted, failed, keptByReference). Exposed `internal` so the
     * test suite can verify the per-file gate in isolation.
     */
    internal fun deleteLegacyFilesStep(
        legacyFiles: Array<File>,
        stillReferenced: Set<String>
    ): Triple<Int, Int, Int> {
        var deleted = 0
        var failed = 0
        var kept = 0
        for (legacy in legacyFiles) {
            if (!legacy.exists()) continue
            if (legacy.absolutePath in stillReferenced) {
                Logger.d(TAG, "step (c): keep ${legacy.name} — " +
                    "still referenced by a note")
                kept++
                continue
            }
            try {
                if (legacy.delete()) {
                    deleted++
                } else {
                    failed++
                    Logger.w(TAG, "could not delete legacy " +
                        "${legacy.absolutePath}")
                }
            } catch (t: Throwable) {
                failed++
                Logger.w(TAG, "delete failed for ${legacy.absolutePath}: " +
                    "${t.message ?: t.javaClass.simpleName}", t)
            }
        }
        return Triple(deleted, failed, kept)
    }

    /**
     * Parse [content] (V2.6 header or JSON v2 envelope), rewrite any
     * `ImageBlock.uri` whose path is the legacy prefix into the new
     * prefix, and serialise the result back to the same wire format.
     * Exposed `internal` so the test suite can drive it directly.
     */
    internal fun rewriteContent(
        content: String,
        legacyPrefix: String,
        newPrefix: String
    ): String {
        if (content.isEmpty()) return content
        val blocks = parseBlocksFromContent(content)
        var mutated = false
        val rewritten = blocks.map { block ->
            if (block is ContentBlock.ImageBlock) {
                val newUri = rewriteImageUri(block.uri, legacyPrefix, newPrefix)
                if (newUri != block.uri) {
                    mutated = true
                    block.copy(uri = newUri)
                } else block
            } else block
        }
        if (!mutated) return content
        return serializeBlocksToContent(rewritten)
    }

    /**
     * Rewrite a single URI. Pure function so it can be unit-tested
     * without going through the full ContentBlock round-trip.
     *
     * Rules:
     *   - Anything that doesn't start with the legacy `file://<root>/`
     *     prefix is returned unchanged (so R2 https:// URLs, content://
     *     URIs, foreign file:// URLs all pass through).
     *   - A URI already in the new `…/images/…` form is returned
     *     unchanged (so the migration is idempotent at the URI level).
     *   - A URI whose legacy-relative path starts with `img_` is
     *     rewritten to `…/images/<rest>`.
     *
     * The legacy prefix ends in `/` and the file basename starts with
     * the path-separator from `Uri.fromFile`, so the substring after
     * the prefix is normally `"/img_<UUID>.<ext>"` (one leading `/`).
     * We strip that before the `img_` check so the prefix is matched
     * regardless of how the caller chose to concatenate.
     */
    internal fun rewriteImageUri(
        uri: String,
        legacyPrefix: String,
        newPrefix: String
    ): String {
        if (!uri.startsWith(legacyPrefix)) return uri
        val relative = uri.substring(legacyPrefix.length)
        val basename = relative.trimStart('/')
        if (basename.startsWith("images/")) return uri
        // Only rewrite the editor's own files. A stray `file://<root>/foo`
        // that some other feature left behind is left alone — we never
        // wrote it, we don't know what it is, and dragging it into
        // `images/` could shadow a future real file.
        if (!basename.startsWith("img_")) return uri
        return newPrefix + basename
    }
}
