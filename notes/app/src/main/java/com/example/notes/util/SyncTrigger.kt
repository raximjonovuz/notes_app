package com.example.notes.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.example.notes.data.remote.NotesSyncRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * F-04 + F-14 — drives background sync from the two network /
 * lifecycle events that should kick the data layer:
 *
 *   1. **Network restored.** The user lost connectivity, was happily
 *      editing offline, and just got it back. Sync should kick
 *      itself without the user having to tap anything.
 *
 *   2. **Cold start.** A fresh process boot — even if the user never
 *      opens the app and pokes the Sync button, the data layer
 *      should reconcile whatever the server has. We fire the same
 *      code path as (1) on application start.
 *
 * Design notes
 * ------------
 *   - The debounce window ([DEBOUNCE_MS]) collapses a burst of
 *     `onAvailable` callbacks — Android can fire several within a
 *     few hundred milliseconds when the user flips airplane mode off
 *     or moves between Wi-Fi and mobile data. Without the debounce
 *     we'd start, cancel, and re-start a sync per callback.
 *
 *   - The `isSyncRunning` guard means a re-entrant trigger (e.g.
 *     network-restored fires while a Sync-button-initiated sync is
 *     still in flight) is a no-op. The
 *     [NotesSyncRepository.syncMutex] already serialises the
 *     actual round-trip, but the guard avoids the wasted context
 *     switches and logging that come with a redundant
 *     "started" → "merged → finished" sequence.
 *
 *   - The trigger is owned by [com.example.notes.NotesApplication]
 *     for the process lifetime. The [ConnectivityManager] callback
 *     is registered with the system; on process death the system
 *     tears it down, so we never unregister manually. The
 *     [stop] method is here for tests and for the (rare)
 *     [Application.onTerminate] hook on emulators.
 *
 *   - The network source uses [callbackFlow] so the registration
 *     and unregistration of the [ConnectivityManager.NetworkCallback]
 *     are tied to the collector's lifetime via [awaitClose]. We
 *     merge it with the manual [requestSync] trigger so both go
 *     through the same debounce + single-flight guard.
 */
@OptIn(FlowPreview::class)
class SyncTrigger(
    private val applicationScope: CoroutineScope,
    private val syncRunner: SyncRunner,
    /**
     * F-04 — the debounce window. Overridable so the unit tests can
     * use a short window instead of waiting 3 real seconds per case.
     */
    private val debounceMs: Long = DEBOUNCE_MS
) {
    /**
     * Minimal callback the trigger calls after debounce. Exposed as
     * a SAM interface so unit tests can supply a counter or a
     * suspending gate without having to mock the full
     * [NotesSyncRepository] (which needs a real [Context], a real
     * [NoteDao], real [AuthStore], and a real [ConnectivityManager]
     * to even construct). Production wires this to
     * `NotesSyncRepository::syncIfOnline` from [NotesApplication].
     *
     * Returns the typed [com.example.notes.data.remote.NotesSyncRepository.SyncOutcome]
     * so the production call site can surface the result (e.g. in
     * logs); the trigger itself does not use the return value —
     * it cares only about side effects.
     */
    fun interface SyncRunner {
        suspend fun run(): com.example.notes.data.remote.NotesSyncRepository.SyncOutcome
    }

    /**
     * F-04 — single-flight guard. See [SingleFlightGate] for the
     * exact semantics.
     */
    private val gate = SingleFlightGate()
    private var collectJob: Job? = null

    /**
     * Manual trigger pipe. The network callback also writes into
     * this flow (via the merged collector below), so the
     * [requestSync] / network paths share the same single-flight
     * guard. [MutableSharedFlow] is used (not a regular channel)
     * so a fast burst of events from [requestSync] doesn't suspend
     * the caller; the extra buffer ensures the caller is never
     * blocked even when the collector is busy.
     */
    private val manualTriggers = MutableSharedFlow<TriggerReason>(
        replay = 0,
        extraBufferCapacity = 16
    )

    /**
     * Start observing network changes. Idempotent — a second
     * `start()` while the collector is already running is a no-op.
     */
    fun start(context: Context) {
        if (collectJob?.isActive == true) return
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return

        collectJob = applicationScope.launch {
            // The Flow-based observer. `onAvailable` is the only event
            // we care about; `onLost` is informational (we don't
            // actively cancel an in-flight sync when connectivity
            // drops, because OkHttp's per-request timeout already
            // handles that).
            val networkFlow: Flow<TriggerReason> = callbackFlow {
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        trySend(TriggerReason.NetworkAvailable)
                    }
                }
                cm.registerDefaultNetworkCallback(callback)
                // awaitClose runs when the collector is cancelled
                // (scope cancellation, stop() call, process death), so
                // we never leak the registered callback.
                awaitClose { cm.unregisterNetworkCallback(callback) }
            }

            merge(networkFlow, manualTriggers)
                .debounce(debounceMs)
                .collect { reason ->
                    // F-04 — launch the sync in a CHILD coroutine
                    // instead of running it inline in the collector.
                    //
                    // Running it inline would make the collector
                    // block for the whole duration of the sync, so a
                    // second trigger couldn't even be *observed*
                    // until the first sync finished — the
                    // single-flight guard would then be dead code
                    // (the previous sync had already released it).
                    //
                    // With a child coroutine the collector stays
                    // responsive, the guard actually rejects a
                    // re-entrant trigger, and a burst of events
                    // during a long sync doesn't queue up more
                    // syncs.
                    applicationScope.launch { fireSync(reason) }
                }
        }
    }

    /**
     * Fire a one-shot sync from outside the network-callback path
     * (cold start, login success, debug hook). Same debounce /
     * single-flight guard as the network path so two rapid calls
     * coalesce into a single sync.
     */
    fun requestSync(reason: TriggerReason) {
        // tryEmit is non-blocking; with the buffer above it
        // succeeds unless 16 events are already pending, which
        // can't happen in practice (events come seconds apart).
        manualTriggers.tryEmit(reason)
    }

    /**
     * Cancel the network observer and any pending debounced
     * trigger. Process death does this for us, but a test that
     * wants a clean state between cases can call it.
     */
    fun stop() {
        collectJob?.cancel()
        collectJob = null
    }

    private suspend fun fireSync(reason: TriggerReason) {
        if (!gate.tryEnter()) {
            Logger.d(
                TAG, "debounced trigger ($reason): " +
                    "sync already running, skipping"
            )
            return
        }
        try {
            Logger.d(TAG, "debounced trigger ($reason): firing sync")
            syncRunner.run()
        } catch (t: Throwable) {
            Logger.w(TAG, "sync from $reason failed", t)
        } finally {
            gate.exit()
        }
    }

    /**
     * The set of reasons the trigger can fire. Captured in logs
     * so we can tell, in a bug report, whether a sync came from a
     * real network flap or from an explicit user / code path.
     */
    enum class TriggerReason { NetworkAvailable, ColdStart, LoginSuccess, Debug }

    companion object {
        private const val TAG = "SyncTrigger"

        /**
         * F-04 — debounce window. Three seconds covers the typical
         * "airplane mode off → Wi-Fi up → mobile data ready" burst
         * (Android can fire [ConnectivityManager.NetworkCallback.onAvailable]
         * 2-3 times in a couple of seconds when several transports
         * come back). 3 s is short enough that a real recovery
         * still feels reactive, long enough that a flap doesn't
         * restart the sync four times in a row.
         */
        private const val DEBOUNCE_MS = 3_000L
    }
}
