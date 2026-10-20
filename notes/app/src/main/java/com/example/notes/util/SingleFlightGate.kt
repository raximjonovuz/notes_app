package com.example.notes.util

import java.util.concurrent.atomic.AtomicBoolean

/**
 * F-04 — a tiny "only one holder at a time" latch built on
 * [AtomicBoolean.compareAndSet].
 *
 * [com.example.notes.util.SyncTrigger] uses it to make sure a second
 * sync isn't started while the first one is still in flight. The
 * semantics are deliberately minimal and side-effect free so the
 * behaviour can be unit-tested directly, without any timing:
 *
 *   - [tryEnter] returns `true` exactly once per "hold" — the first
 *     caller wins, every concurrent caller gets `false`.
 *   - [exit] releases the latch so the next [tryEnter] succeeds.
 *   - [exit] is idempotent — calling it without a matching [tryEnter]
 *     is harmless.
 *
 * The implementation is a single atomic CAS, so it is correct under
 * real concurrency (multiple threads / dispatchers) — not just
 * under a sequential test.
 */
class SingleFlightGate {

    private val held = AtomicBoolean(false)

    /**
     * Try to acquire the latch.
     *
     * @return `true` if the caller acquired it (and MUST call
     *   [exit] when done), `false` if somebody else already holds it.
     */
    fun tryEnter(): Boolean = held.compareAndSet(false, true)

    /**
     * Release the latch. Safe to call even when the latch wasn't
     * held.
     */
    fun exit() {
        held.set(false)
    }

    /**
     * Whether the latch is currently held. Exposed for diagnostics
     * and tests.
     */
    val isHeld: Boolean get() = held.get()
}
