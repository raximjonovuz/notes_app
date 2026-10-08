package com.example.notes.data.local

import android.content.Context
import androidx.core.content.edit
import com.example.notes.data.remote.ApiClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persistent storage for **multiple** user sessions plus a pointer to the
 * currently-active one. The store keeps at most [MAX_SESSIONS] accounts on
 * disk (3 by default). Adding a new session while the limit is full evicts
 * the least-recently-used one.
 *
 * Layout:
 *   - `sessions_json`   -> JSON-encoded list of [Session] objects
 *   - `active_email`    -> email of the currently selected account
 *
 * On read, the active session is also pushed into [ApiClient.authToken] so
 * subsequent HTTP requests are authenticated without an explicit login.
 */
object AuthStore {

    private const val PREFS = "auth_prefs"
    private const val KEY_SESSIONS = "sessions_json"
    private const val KEY_ACTIVE_EMAIL = "active_email"

    /** Maximum number of accounts remembered on this device. */
    const val MAX_SESSIONS = 3

    @Serializable
    data class Session(
        val email: String,
        val userId: Int,
        val token: String,
        /** Epoch-ms of the last time this session was used (sign-in or switch). */
        val lastUsedAtMs: Long = System.currentTimeMillis()
    )

    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // ---------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------

    /**
     * Returns every saved session, most-recently-used first. Does **not**
     * restore the [ApiClient] token — use [getActive] or [setActive] for
     * that.
     */
    fun loadAll(context: Context): List<Session> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_SESSIONS, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<Session>>(raw)
                .sortedByDescending { it.lastUsedAtMs }
        } catch (t: Throwable) {
            // Corrupted blob — wipe it so the user is not stuck.
            prefs.edit { remove(KEY_SESSIONS).remove(KEY_ACTIVE_EMAIL) }
            emptyList()
        }
    }

    /**
     * Returns the currently-active session (or null if none is selected or
     * the saved session is no longer in the list). As a side-effect this
     * also restores the bearer token on [ApiClient] so subsequent calls are
     * authenticated.
     */
    fun getActive(context: Context): Session? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val activeEmail = prefs.getString(KEY_ACTIVE_EMAIL, null) ?: return null
        val session = loadAll(context).firstOrNull { it.email == activeEmail }
            ?: run {
                // Active email points to a missing session — clear it.
                prefs.edit { remove(KEY_ACTIVE_EMAIL) }
                return null
            }
        ApiClient.authToken = session.token
        return session
    }

    /**
     * Inserts a new session or updates the existing row matched by email (in
     * which case the token is refreshed and `lastUsedAtMs` bumped). Enforces
     * the [MAX_SESSIONS] cap by evicting the oldest session if necessary.
     * Also sets the just-saved session as the active one.
     */
    fun saveOrUpdate(context: Context, session: Session) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = loadAll(context).toMutableList()
        val idx = existing.indexOfFirst { it.email == session.email }
        val now = System.currentTimeMillis()
        val updated = session.copy(lastUsedAtMs = now)
        if (idx >= 0) {
            existing[idx] = updated
        } else {
            existing.add(updated)
        }
        // Evict oldest beyond cap.
        val trimmed = existing
            .sortedByDescending { it.lastUsedAtMs }
            .take(MAX_SESSIONS)

        val blob = json.encodeToString(trimmed)
        prefs.edit {
            putString(KEY_SESSIONS, blob)
            putString(KEY_ACTIVE_EMAIL, updated.email)
        }
        ApiClient.authToken = updated.token
    }

    /**
     * Marks [email]'s session as active and pushes its token into
     * [ApiClient]. If [email] does not match any saved session this is a
     * no-op (returns false).
     */
    fun setActive(context: Context, email: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val match = loadAll(context).firstOrNull { it.email == email } ?: return false
        val bumped = match.copy(lastUsedAtMs = System.currentTimeMillis())
        val all = loadAll(context)
            .map { if (it.email == email) bumped else it }
            .sortedByDescending { it.lastUsedAtMs }
            .take(MAX_SESSIONS)
        prefs.edit {
            putString(KEY_SESSIONS, json.encodeToString(all))
            putString(KEY_ACTIVE_EMAIL, bumped.email)
        }
        ApiClient.authToken = bumped.token
        return true
    }

    /**
     * Removes a single session. If the removed session was active, the
     * most-recently-used remaining session (or none) becomes active.
     */
    fun remove(context: Context, email: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val remaining = loadAll(context).filterNot { it.email == email }
        val active = prefs.getString(KEY_ACTIVE_EMAIL, null)
        prefs.edit {
            putString(KEY_SESSIONS, json.encodeToString(remaining))
            if (active == email) {
                if (remaining.isEmpty()) {
                    remove(KEY_ACTIVE_EMAIL)
                } else {
                    putString(KEY_ACTIVE_EMAIL, remaining.first().email)
                    ApiClient.authToken = remaining.first().token
                }
            }
        }
        if (remaining.isEmpty()) {
            ApiClient.authToken = null
        }
    }

    /** Removes every saved session and clears [ApiClient]'s bearer token. */
    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            remove(KEY_SESSIONS)
            remove(KEY_ACTIVE_EMAIL)
        }
        ApiClient.authToken = null
    }

    /**
     * Clears only the *active* pointer. Saved sessions are kept so the user
     * can re-sign-in later or pick a different one from the Login screen
     * without having to retype email + password.
     */
    fun clearActive(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            remove(KEY_ACTIVE_EMAIL)
        }
        ApiClient.authToken = null
    }

    // ---------------------------------------------------------------
    // Backwards-compat shims (V3.1 callers — call sites have been updated
    // but the old single-session shape is still referenced in a couple of
    // places, so keep these convenience wrappers for now).
    // ---------------------------------------------------------------

    /** @deprecated use [saveOrUpdate] which also activates the session. */
    @Deprecated("Use saveOrUpdate() to also activate the saved session.")
    fun save(context: Context, session: Session) = saveOrUpdate(context, session)

    /** @deprecated use [loadAll] or [getActive] instead. */
    @Deprecated("Returns the active session — use getActive() instead.")
    fun load(context: Context): Session? = getActive(context)

    /** @deprecated use [remove] or [clearAll] depending on intent. */
    @Deprecated("Clears everything — use clearAll() or remove(email) instead.")
    fun clear(context: Context) = clearAll(context)
}

/**
 * Lightweight persistence for the timestamp of the last successful sync.
 * The server uses millisecond epoch timestamps; null means "never synced,
 * fetch everything".
 */
object SyncStore {

    private const val PREFS = "sync_prefs"
    private const val KEY_LAST_SYNC = "last_sync"

    fun getLastSync(context: Context): Long? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY_LAST_SYNC)) prefs.getLong(KEY_LAST_SYNC, 0L) else null
    }

    fun setLastSync(context: Context, timestamp: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putLong(KEY_LAST_SYNC, timestamp)
        }
    }
}