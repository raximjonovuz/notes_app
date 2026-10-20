package com.example.notes.util

import android.content.Context

/**
 * F-05 — per-note retry counter for the image-upload deferral loop
 * in [com.example.notes.data.remote.NotesSyncRepository].
 *
 * The editor writes images into the local sandbox under
 * `filesDir/images/img_<UUID>.<ext>` and the note's content
 * references them via `file://<filesDir>/images/img_X`. The push
 * pass defers notes that still carry a local `file://` URI until
 * the editor's [com.example.notes.ui.edit.NoteEditViewModel] upload
 * completes and rewrites the block to the public R2 URL.
 *
 * If the upload is broken (server 5xx, R2 quota, network down), the
 * old code rescheduled every 5 s forever — meaning the note's text
 * also never got pushed (the deferral gate sits in front of the
 * push queue). This store gives up on a note after
 * [MAX_RETRIES] attempts: the next sync push sends the note as-is,
 * with the local `file://` URI preserved. The text is preserved on
 * the server; the image is lost from the server's perspective
 * (the local file still exists on the device, so the user can
 * re-share / re-edit).
 *
 * The counter is keyed by note id, not by image URI, because:
 *   - the same note can only have one pending image at a time
 *     (the editor's per-note save flow replaces the local URI as
 *     soon as the R2 upload rewrites the block);
 *   - a counter per URI would need to be cleared when the URI
 *     changes, which adds a second failure point.
 */
object ImageRetryStore {

    private const val TAG = "ImageRetryStore"
    private const val PREFS = "image_retry"
    private const val KEY_PREFIX = "img_retry_"

    /**
     * F-05 — hard cap on how many times [com.example.notes.data.remote
     * .NotesSyncRepository.schedulePendingImagePush] will reschedule
     * itself for a single note. After the cap is reached the note is
     * pushed on the next sync attempt with the local `file://` URI
     * left in place — the user keeps the text on the server at the
     * cost of the image.
     *
     * At the default [PUSH_DEBOUNCE_MS] of 5 s, 5 retries = 25 s,
     * long enough to ride out a typical R2 quota or temporary
     * backend outage but short enough to not block the text sync
     * for users with persistent upload problems.
     */
    const val MAX_RETRIES = 5

    private fun key(noteId: Long): String = "$KEY_PREFIX$noteId"

    /**
     * @return `true` when the note has hit or exceeded
     * [MAX_RETRIES] consecutive deferred push attempts and the
     * push should no longer be blocked.
     */
    fun noteGaveUpImage(context: Context, noteId: Long): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getInt(key(noteId), 0) >= MAX_RETRIES
    }

    /**
     * Increment the retry counter for [noteId] and return whether
     * this increment has just pushed the note over the
     * [MAX_RETRIES] threshold. Idempotent against a counter that
     * was already at the cap (returns `true` without writing again).
     */
    fun incrementAndCheck(context: Context, noteId: Long): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = key(noteId)
        val current = prefs.getInt(k, 0)
        if (current >= MAX_RETRIES) return true
        val newCount = current + 1
        prefs.edit().putInt(k, newCount).apply()
        return newCount >= MAX_RETRIES
    }

    /**
     * Drop the retry counter for [noteId]. Called when the image
     * eventually uploads successfully (the editor's [markSynced]
     * has replaced the local URI with the public R2 URL), or when
     * the note is deleted — either way, the deferral gate no longer
     * applies and a fresh attempt budget should be available the
     * next time the user adds an image to this note.
     */
    fun reset(context: Context, noteId: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().remove(key(noteId)).apply()
    }
}
