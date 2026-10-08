package com.example.notes.util

import android.util.Log
import com.example.notes.BuildConfig

/**
 * V4.4 — minimal logging wrapper. All call sites used to call
 * `android.util.Log.d(...)` directly which (a) printed in release builds
 * with full user data and (b) couldn't be turned off at runtime. Going
 * through this helper means:
 *
 *   - In debug builds every level is printed as-is.
 *   - In release builds only WARN/ERROR are forwarded to logcat; INFO/DEBUG
 *     are dropped, so PII (note titles, JWTs, etc.) never leaves the device.
 *
 * If you ever need to enable verbose logging on a production device for
 * support purposes, flip [forceVerbose] from a hidden Settings toggle.
 *
 * V5.0.2 — every [Log] call is wrapped in a try/catch because the
 * `android.util.Log` stub installed by Android's plain unit-test
 * classpath throws `RuntimeException("Method ... not mocked")` for
 * `Log.w` / `Log.e` / `Log.i` calls. Production code never sees the
 * exception (a real device always has a working Log); tests run
 * silently instead of failing the build.
 */
object Logger {
    @Volatile
    var forceVerbose: Boolean = false

    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG || forceVerbose) safeLog { Log.d(tag, message) }
    }

    fun i(tag: String, message: String) {
        if (BuildConfig.DEBUG || forceVerbose) safeLog { Log.i(tag, message) }
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        // Warnings are always logged — they signal degraded behaviour the
        // user should be able to retrieve via `adb logcat`.
        safeLog {
            if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        safeLog {
            if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
        }
    }

    private inline fun safeLog(block: () -> Unit) {
        try {
            block()
        } catch (_: RuntimeException) {
            // `android.util.Log` is a stub on the plain JVM unit-test
            // classpath; ignore so a debug print never breaks tests.
        }
    }
}
