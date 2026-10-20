package com.example.notes.ui.splash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NoteAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.notes.util.LocalImageMigration
import com.example.notes.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Hard upper bound on how long the splash is willing to wait for
 * the image-path migration to settle. In practice the migration
 * finishes in well under a second (a few dozen file copies plus a
 * handful of DB updates); the timeout is a safety net for the
 * pathological case where the IO scope somehow hangs (e.g. a
 * blocked file handle on external storage).
 *
 * On timeout the splash proceeds to the rest of the UI anyway —
 * the user gets to see their notes rather than a stuck splash
 * screen. The migration keeps running in the background; the
 * editor's per-note save (which goes through the same DAO path
 * the migration uses) will serialise behind the in-flight work
 * via Room's single-writer connection.
 */
private const val SPLASH_GATE_TIMEOUT_MS = 10_000L

@Composable
fun SplashScreen(onTimeout: () -> Unit) {
    // V5.3.4 / F-28 — wait for the one-time image-path migration to
    // settle before opening the rest of the UI. Two reasons:
    //   1. So the user never opens a note whose `file://` URI still
    //      points at the legacy path while the migration is in flight.
    //   2. So the editor can't load a stale URI and then have its
    //      backing file deleted out from under it before the editor
    //      re-renders.
    //
    // The migration ALWAYS resolves `completionSignal` — even on
    // error — so the inner await normally returns immediately. The
    // outer [withTimeoutOrNull] is a safety net: if the IO scope
    // somehow hangs (e.g. blocked storage, a future regression
    // that swallows the completion), we still open the UI after
    // [SPLASH_GATE_TIMEOUT_MS] rather than leave the user staring
    // at a splash forever.
    LaunchedEffect(Unit) {
        try {
            val gate = withTimeoutOrNull(SPLASH_GATE_TIMEOUT_MS) {
                LocalImageMigration.completionSignal.await()
            }
            if (gate == null) {
                Logger.w(
                    "SplashScreen",
                    "image migration gate timed out after " +
                        "${SPLASH_GATE_TIMEOUT_MS}ms; opening UI anyway"
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Defensive: [completionSignal] is always completed normally
            // today, so this branch is unreachable in practice. If the
            // implementation ever changes to use completeExceptionally,
            // we still don't want the splash to block the UI.
            Logger.w("SplashScreen",
                "unexpected error awaiting image migration", t)
        }
        // Keep the small minimum-display-time so the splash
        // doesn't flash when migration is already done (typical
        // every cold start after the first one).
        delay(300)
        onTimeout()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.NoteAlt,
                contentDescription = "App logo",
                modifier = Modifier.size(96.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = "Notes",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(48.dp))
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Loading...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}