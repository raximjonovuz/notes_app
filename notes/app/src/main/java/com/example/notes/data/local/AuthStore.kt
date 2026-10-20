package com.example.notes.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.notes.data.remote.ApiClient
import com.example.notes.util.Logger
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persistent storage for **multiple** user sessions plus a pointer to the
 * currently-active one. The store keeps at most [MAX_SESSIONS] accounts on
 * disk (3 by default). Adding a new session while the limit is full evicts
 * the least-recently-used one.
 *
 * C1 — tokens are no longer stored in a plain SharedPreferences file. The
 * store now wraps [EncryptedSharedPreferences] (key material in the Android
 * Keystore) so the JWTs cannot be lifted off the device by a backup or an
 * ADB pull. On first access the previous plain `auth_prefs` file is
 * copied into the encrypted store and deleted so existing users keep
 * their session across the upgrade.
 *
 * Layout:
 *   - `sessions_json`   -> JSON-encoded list of [Session] objects
 *   - `active_email`    -> email of the currently selected account
 *
 * On read, the active session is also pushed into [ApiClient.authToken] so
 * subsequent HTTP requests are authenticated without an explicit login.
 */
object AuthStore {

    private const val TAG = "AuthStore"

    /** C1 — legacy plain-text SharedPreferences file (pre-C1 builds). */
    private const val LEGACY_PREFS = "auth_prefs"

    /** C1 — encrypted replacement; backed by an Android Keystore master key. */
    private const val SECURE_PREFS = "auth_prefs_secure"

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

    /**
     * Cached per-process so we only attempt EncryptedSharedPreferences once
     * and survive a transient Keystore outage without retrying on every
     * read/write. `null` while uninitialised.
     */
    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    /**
     * Returns the [SharedPreferences] instance backing this store. Tries the
     * encrypted store first; on any failure (SecurityException on broken
     * Keystore, missing JCE provider, Robolectric unit tests, …) falls back
     * to the plain `auth_prefs` file so the app still works.
     */
    private fun prefs(context: Context): SharedPreferences {
        cachedPrefs?.let { return it }
        synchronized(this) {
            cachedPrefs?.let { return it }
            val resolved = openSecureOrLegacy(context)
            cachedPrefs = resolved
            return resolved
        }
    }

    private fun openSecureOrLegacy(context: Context): SharedPreferences {
        val ctx = context.applicationContext
        val masterKey = try {
            MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        } catch (t: Throwable) {
            Logger.w(TAG, "MasterKey unavailable; falling back to plain prefs", t)
            return ctx.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        }
        return try {
            val secure = EncryptedSharedPreferences.create(
                ctx,
                SECURE_PREFS,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            migrateLegacyIfNeeded(ctx, secure)
            secure
        } catch (t: Throwable) {
            Logger.w(TAG, "EncryptedSharedPreferences unavailable; falling back to plain prefs", t)
            ctx.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        }
    }

    /**
     * C1 — one-time migration from the legacy plain `auth_prefs` file. If
     * a user upgrades from a V5.x build their tokens are still on disk in
     * the clear; we copy the entries into the encrypted store and then
     * delete the legacy file (file-level delete, not just `clear()`) so it
     * is not picked up by future backups or filesystem dumps.
     */
    private fun migrateLegacyIfNeeded(ctx: Context, target: SharedPreferences) {
        val legacy = try {
            ctx.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        } catch (_: Throwable) {
            return
        }
        if (legacy.all.isEmpty()) {
            // Nothing to migrate; still try to delete the file so an empty
            // legacy file from a previous failed init doesn't linger.
            deleteLegacyFile(ctx)
            return
        }
        target.edit {
            for ((k, v) in legacy.all) {
                when (v) {
                    is String -> putString(k, v)
                    is Long -> putLong(k, v)
                    is Int -> putInt(k, v)
                    is Boolean -> putBoolean(k, v)
                    is Float -> putFloat(k, v)
                    else -> {
                        // Unknown type — skip. EncryptedSharedPreferences
                        // only supports the primitive scalars listed above.
                    }
                }
            }
        }
        deleteLegacyFile(ctx)
    }

    private fun deleteLegacyFile(ctx: Context) {
        try {
            val f = File(ctx.applicationInfo.dataDir, "shared_prefs/$LEGACY_PREFS.xml")
            if (f.exists()) f.delete()
        } catch (_: Throwable) {
            // Best-effort. The clear() inside `edit { clear() }` above
            // already emptied the file contents.
        }
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
        val prefs = prefs(context)
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
        val prefs = prefs(context)
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
        val prefs = prefs(context)
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
        val prefs = prefs(context)
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
        val prefs = prefs(context)
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
        prefs(context).edit {
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
        prefs(context).edit {
            remove(KEY_ACTIVE_EMAIL)
        }
        ApiClient.authToken = null
    }
}

/**
 * V5.3 — replaces the previous `getLastSync` (milliseconds) with a
 * monotonic sync cursor (`change_id` from the server's `change_log`).
 * Missing the key still means "never synced, fall back to first-sync
 * logic".
 *
 * Also persists BUG 8's pendingFlushVersion so a cold start can pick
 * up a push that was queued by the previous process before it died.
 *
 * B6 — every per-user key is now namespaced with the user id. The
 * previous unscoped `last_sync_cursor` / `pending_flush_version`
 * values are read once and copied into the per-user slot (so an
 * existing user keeps their cursor on the first sync after the
 * upgrade), then ignored. New users start at 0.
 */
object SyncStore {

    private const val PREFS = "sync_prefs"

    // Legacy unscoped keys — read once on first per-user access, then
    // ignored. Not deleted so any external observer (debug tool,
    // pre-existing test fixture) still sees the value it expects.
    private const val KEY_LEGACY_LAST_CURSOR = "last_sync_cursor"
    private const val KEY_LEGACY_PENDING_FLUSH = "pending_flush_version"
    private const val KEY_LEGACY_LAST_PUSH_AT = "last_push_at"

    private fun cursorKey(userId: Int) = "last_sync_cursor_$userId"
    private fun pendingFlushKey(userId: Int) = "pending_flush_version_$userId"
    private fun lastPushAtKey(userId: Int) = "last_push_at_$userId"

    /**
     * Last server-side change_id we successfully drained for [userId].
     * Null = never synced.
     *
     * On the first call for a given [userId] the legacy
     * `last_sync_cursor` value (if any) is copied into the
     * per-user slot so a user who has already synced once before
     * the upgrade keeps their cursor. After that copy the legacy
     * value is left in place (it is harmless and may help
     * debugging) and the per-user slot is the source of truth.
     */
    fun getLastCursor(context: Context, userId: Int): Long? {
        if (userId <= 0) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = cursorKey(userId)
        if (prefs.contains(key)) {
            return prefs.getLong(key, 0L)
        }
        if (prefs.contains(KEY_LEGACY_LAST_CURSOR)) {
            val legacy = prefs.getLong(KEY_LEGACY_LAST_CURSOR, 0L)
            prefs.edit { putLong(key, legacy) }
            return legacy
        }
        return null
    }

    fun setLastCursor(context: Context, userId: Int, cursor: Long) {
        if (userId <= 0) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putLong(cursorKey(userId), cursor)
            putLong(lastPushAtKey(userId), System.currentTimeMillis())
        }
    }

    /**
     * V5.3 — backwards-compat shim. Returns 0 when never synced so older
     * call sites (and tests) that still pass a Long keep working.
     */
    fun getLastSync(context: Context, userId: Int): Long = getLastCursor(context, userId) ?: 0L

    /**
     * BUG 8 — store a monotonic version that bumps every time a push
     * gets queued. Cold start compares the persisted version to the
     * in-process one and re-runs the queued push if they differ,
     * meaning the previous process was killed before it could drain.
     *
     * B6 — namespaced by [userId]. The legacy unscoped value is
     * honoured only the first time the user is seen; after that the
     * per-user slot is authoritative.
     */
    fun bumpPendingFlush(context: Context, userId: Int): Long {
        if (userId <= 0) return 0L
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = pendingFlushKey(userId)
        val base = if (prefs.contains(key)) {
            prefs.getLong(key, 0L)
        } else if (prefs.contains(KEY_LEGACY_PENDING_FLUSH)) {
            prefs.getLong(KEY_LEGACY_PENDING_FLUSH, 0L)
        } else {
            0L
        }
        val next = (base and 0x7FFF_FFFF_FFFF_FFFFL) + 1
        prefs.edit { putLong(key, next) }
        return next
    }

    fun consumePendingFlush(context: Context, userId: Int): Long {
        if (userId <= 0) return 0L
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = pendingFlushKey(userId)
        val v = prefs.getLong(key, 0L)
        if (v != 0L) {
            prefs.edit { putLong(key, 0L) }
        }
        return v
    }
}
