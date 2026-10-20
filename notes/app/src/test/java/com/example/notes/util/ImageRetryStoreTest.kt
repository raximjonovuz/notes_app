package com.example.notes.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F-05 — tests for the per-note image-upload retry budget. The
 * counter is the single source of truth for the push pass's
 * "should I keep deferring?" decision: when a note's counter hits
 * [ImageRetryStore.MAX_RETRIES] the next push sends it as-is
 * (preserving the local `file://` URI on the server), unblocking
 * the note's text sync.
 *
 * The contract we pin here:
 *   1. `noteGaveUpImage` is `false` until the counter reaches
 *      [ImageRetryStore.MAX_RETRIES].
 *   2. `incrementAndCheck` is monotonic per note: the counter
 *      only goes up, never down (except via an explicit
 *      `reset`).
 *   3. `reset` clears the counter so the NEXT image the user
 *      adds to the same note gets a fresh [MAX_RETRIES] budget.
 *   4. Incrementing past [MAX_RETRIES] still reports `true` (so
 *      a re-entrant call after the cap doesn't accidentally
 *      un-give-up the note).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = com.example.notes.TestNotesApplication::class)
class ImageRetryStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Clean state — each test creates a fresh noteId so they
        // don't bleed into each other, but be defensive anyway.
        clearAll()
    }

    @After
    fun tearDown() {
        clearAll()
    }

    private fun clearAll() {
        val prefs = context.getSharedPreferences("image_retry", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    @Test
    fun `noteGaveUpImage is false before MAX_RETRIES, true at and after`() {
        val noteId = 1L
        assertFalse(
            "fresh note must not be marked gave-up",
            ImageRetryStore.noteGaveUpImage(context, noteId)
        )
        for (i in 1 until ImageRetryStore.MAX_RETRIES) {
            val gaveUp = ImageRetryStore.incrementAndCheck(context, noteId)
            assertFalse(
                "increment #$i (under cap) must not signal gave-up",
                gaveUp
            )
            assertFalse(
                "noteGaveUpImage after #$i increments must still be false",
                ImageRetryStore.noteGaveUpImage(context, noteId)
            )
        }
        // The MAX_RETRIES'th increment is the one that flips the
        // flag to "gave up".
        val finalGaveUp = ImageRetryStore.incrementAndCheck(context, noteId)
        assertTrue(
            "increment at MAX_RETRIES=${ImageRetryStore.MAX_RETRIES} " +
                "must signal gave-up",
            finalGaveUp
        )
        assertTrue(
            "noteGaveUpImage after the cap must be true",
            ImageRetryStore.noteGaveUpImage(context, noteId)
        )
        // Incrementing past the cap stays at "gave up" — the
        // counter doesn't keep climbing forever, the flag stays
        // true, and no state is changed (so a re-entrant call
        // doesn't accidentally un-flag the note).
        val stillGaveUp = ImageRetryStore.incrementAndCheck(context, noteId)
        assertTrue(
            "increment past the cap must still be gave-up",
            stillGaveUp
        )
    }

    @Test
    fun `reset clears the budget so a future image gets MAX_RETRIES again`() {
        val noteId = 2L
        // Push the counter to the cap.
        repeat(ImageRetryStore.MAX_RETRIES) {
            ImageRetryStore.incrementAndCheck(context, noteId)
        }
        assertTrue(
            "sanity: note must be gave-up at the cap",
            ImageRetryStore.noteGaveUpImage(context, noteId)
        )
        // After a successful push the editor drops the local URI
        // (replaced with the public R2 URL) and the migration
        // reset()s the counter. The next image the user adds to
        // the same note should get a fresh budget.
        ImageRetryStore.reset(context, noteId)
        assertFalse(
            "reset must clear gave-up so a fresh image has a full budget",
            ImageRetryStore.noteGaveUpImage(context, noteId)
        )
        // Counter starts at 1 again on next increment.
        val firstIncrementAfterReset = ImageRetryStore.incrementAndCheck(context, noteId)
        assertFalse(
            "first increment after reset must not be gave-up",
            firstIncrementAfterReset
        )
    }

    @Test
    fun `different notes have independent counters`() {
        val noteA = 10L
        val noteB = 20L
        // Drive noteA to the cap.
        repeat(ImageRetryStore.MAX_RETRIES) {
            ImageRetryStore.incrementAndCheck(context, noteA)
        }
        assertTrue(
            "noteA must be gave-up",
            ImageRetryStore.noteGaveUpImage(context, noteA)
        )
        assertFalse(
            "noteB must NOT be gave-up (independent counter)",
            ImageRetryStore.noteGaveUpImage(context, noteB)
        )
        // Resetting noteA must not touch noteB.
        ImageRetryStore.reset(context, noteA)
        assertFalse(
            "reset(noteA) must not affect noteB",
            ImageRetryStore.noteGaveUpImage(context, noteB)
        )
    }
}
