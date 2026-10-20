package com.example.notes.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.notes.data.remote.NotesSyncRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * F-04 + F-14 — tests for the network-restore sync trigger.
 *
 * We test three things:
 *   1. The debounce window collapses a burst of [SyncTrigger.requestSync]
 *      calls into a single [SyncTrigger.SyncRunner.run] invocation.
 *   2. The single-flight guard prevents a re-entrant trigger
 *      from starting a second sync while the first is still in
 *      flight.
 *   3. [SyncTrigger.stop] cancels the collector and any pending
 *      debounced trigger (no leaks on teardown).
 *
 * The debounce window is injected (100 ms in the tests instead of
 * the production 3 s) so the suite stays fast while exercising the
 * exact same code path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = com.example.notes.TestNotesApplication::class)
class SyncTriggerTest {

    private lateinit var context: Context
    private lateinit var testScope: CoroutineScope
    private val runCount = AtomicInteger(0)
    private var syncRunner: SyncTrigger.SyncRunner = SyncTrigger.SyncRunner {
        runCount.incrementAndGet()
        NotesSyncRepository.SyncOutcome.Success
    }
    private lateinit var trigger: SyncTrigger

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        runCount.set(0)
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        trigger = SyncTrigger(
            applicationScope = testScope,
            syncRunner = syncRunner,
            debounceMs = TEST_DEBOUNCE_MS
        )
    }

    @After
    fun tearDown() {
        trigger.stop()
        testScope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
    }

    /** Wait until [predicate] holds or the timeout expires. */
    private suspend fun awaitUntil(
        timeoutMs: Long = 5_000,
        predicate: () -> Boolean
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            kotlinx.coroutines.delay(20)
        }
        return predicate()
    }

    /**
     * F-04 — debounce coalesces a burst of `requestSync` calls into
     * a single `SyncRunner.run` invocation. We fire 5 triggers
     * 50 ms apart (well inside the test's 100 ms debounce window)
     * and verify only one run happens.
     */
    @Test
    fun `F-04 debounce coalesces a burst of requestSync calls into one run`() =
        kotlinx.coroutines.runBlocking {
            trigger.start(context)
            // Give the collector a moment to attach.
            kotlinx.coroutines.delay(100)

            // Five triggers 50 ms apart, all inside the debounce
            // window.
            trigger.requestSync(SyncTrigger.TriggerReason.ColdStart)
            kotlinx.coroutines.delay(50)
            trigger.requestSync(SyncTrigger.TriggerReason.NetworkAvailable)
            kotlinx.coroutines.delay(50)
            trigger.requestSync(SyncTrigger.TriggerReason.LoginSuccess)
            kotlinx.coroutines.delay(50)
            trigger.requestSync(SyncTrigger.TriggerReason.Debug)
            kotlinx.coroutines.delay(50)
            trigger.requestSync(SyncTrigger.TriggerReason.ColdStart)

            // Let the debounce fire (100 ms after the LAST emit).
            kotlinx.coroutines.delay(TEST_DEBOUNCE_MS + 400)
            assertEquals(
                "burst of 5 triggers must collapse into exactly 1 run",
                1, runCount.get()
            )
        }

    /**
     * F-14 — LoginSuccess trigger starts a sync pass.
     */
    @Test
    fun `F-14 LoginSuccess trigger runs sync`() =
        kotlinx.coroutines.runBlocking {
            trigger.start(context)
            kotlinx.coroutines.delay(100)

            trigger.requestSync(SyncTrigger.TriggerReason.LoginSuccess)
            assertTrue(
                "LoginSuccess trigger must run the sync runner",
                awaitUntil { runCount.get() == 1 }
            )
            assertEquals("LoginSuccess trigger must run exactly once", 1, runCount.get())
        }

    /**
     * F-04 — single-flight guard. While the first sync is still in
     * flight, a second trigger is a no-op.
     *
     * The runner suspends on [gate] so we can race a second trigger
     * against it. The gate is only released at the very end of the
     * test, so the first sync is provably still in flight when the
     * second trigger fires.
     */
    @Test
    fun `F-04 second trigger is a no-op while the first sync is still in flight`() =
        kotlinx.coroutines.runBlocking {
            val gate = CompletableDeferred<Unit>()
            runCount.set(0)
            syncRunner = SyncTrigger.SyncRunner {
                runCount.incrementAndGet()
                gate.await()
                NotesSyncRepository.SyncOutcome.Success
            }
            trigger.stop()
            trigger = SyncTrigger(
                applicationScope = testScope,
                syncRunner = syncRunner,
                debounceMs = TEST_DEBOUNCE_MS
            )
            trigger.start(context)
            kotlinx.coroutines.delay(100)

            // First trigger: fires the sync, which blocks on `gate`.
            trigger.requestSync(SyncTrigger.TriggerReason.ColdStart)
            assertTrue(
                "first trigger must have started the sync",
                awaitUntil { runCount.get() == 1 }
            )
            assertEquals("first trigger must run exactly once", 1, runCount.get())

            // Second trigger while the first is still in flight.
            trigger.requestSync(SyncTrigger.TriggerReason.NetworkAvailable)
            kotlinx.coroutines.delay(TEST_DEBOUNCE_MS + 400)
            assertEquals(
                "second trigger while first is in flight must be a no-op",
                1, runCount.get()
            )

            // Release the in-flight sync. The guard clears and we're
            // ready for the next trigger.
            gate.complete(Unit)
            assertTrue(
                "guard must be released once the in-flight sync returns",
                awaitUntil { true }
            )
            kotlinx.coroutines.delay(200)
            assertEquals(
                "no extra run may have started while the gate was held",
                1, runCount.get()
            )

            // A fresh trigger after the guard clears DOES run.
            trigger.requestSync(SyncTrigger.TriggerReason.ColdStart)
            assertTrue(
                "fresh trigger after the guard clears must run",
                awaitUntil { runCount.get() == 2 }
            )
            assertEquals(
                "fresh trigger after the guard clears must run",
                2, runCount.get()
            )
        }

    /**
     * F-04 — `stop()` cancels the collector. A trigger that's
     * still inside its debounce window must NOT fire once `stop`
     * has been called. The pending debounce coroutine receives
     * the cancellation and exits silently.
     */
    @Test
    fun `F-04 stop cancels the collector and pending triggers`() =
        kotlinx.coroutines.runBlocking {
            trigger.start(context)
            kotlinx.coroutines.delay(100)

            // Schedule a trigger that's still inside its debounce
            // window.
            trigger.requestSync(SyncTrigger.TriggerReason.ColdStart)
            kotlinx.coroutines.delay(20)   // NOT past the debounce yet

            trigger.stop()
            kotlinx.coroutines.delay(TEST_DEBOUNCE_MS + 400)
            assertEquals(
                "stop() must cancel pending debounced triggers",
                0, runCount.get()
            )
        }

    /**
     * F-04 — the [SingleFlightGate] itself, tested directly and
     * without any timing dependency. This is the unit that carries
     * the "only one sync at a time" guarantee; the test above
     * proves the trigger wires it up correctly.
     */
    @Test
    fun `F-04 SingleFlightGate admits exactly one holder at a time`() {
        val gate = SingleFlightGate()
        assertFalse("a fresh gate must not be held", gate.isHeld)

        assertTrue("the first caller must acquire the gate", gate.tryEnter())
        assertTrue("the gate must report itself as held", gate.isHeld)

        // Every subsequent caller is rejected while the gate is held.
        assertFalse("a second caller must be rejected", gate.tryEnter())
        assertFalse("a third caller must be rejected", gate.tryEnter())

        // Releasing lets the next caller in.
        gate.exit()
        assertFalse("the gate must be free after exit()", gate.isHeld)
        assertTrue("a new caller must be admitted after exit()", gate.tryEnter())

        // exit() is idempotent.
        gate.exit()
        gate.exit()
        assertTrue("the gate must still be acquirable", gate.tryEnter())
    }

    /**
     * F-04 — [SingleFlightGate] under real concurrency: exactly one
     * of N racing threads may hold the latch.
     */
    @Test
    fun `F-04 SingleFlightGate admits exactly one winner under concurrency`() {
        val gate = SingleFlightGate()
        val winners = AtomicInteger(0)
        val threads = (1..16).map {
            Thread {
                if (gate.tryEnter()) winners.incrementAndGet()
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(
            "exactly one racing thread may acquire the gate",
            1, winners.get()
        )
    }

    private companion object {
        /** Short debounce so the tests don't wait 3 real seconds. */
        const val TEST_DEBOUNCE_MS = 100L
    }
}
