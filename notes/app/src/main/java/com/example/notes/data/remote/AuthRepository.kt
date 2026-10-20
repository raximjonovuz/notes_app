package com.example.notes.data.remote

import android.content.Context
import com.example.notes.data.local.AuthStore
import com.example.notes.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thin facade over [ApiClient] auth endpoints that also persists the
 * resulting session to [AuthStore]. Callers (login screen / settings
 * screen) only need this single entry-point.
 *
 * Supports multiple saved accounts on the same device — at most
 * [AuthStore.MAX_SESSIONS] (currently 3). The "active" account is the one
 * whose JWT is currently set as [ApiClient.authToken].
 */
class AuthRepository(
    private val context: Context,
    private val onSessionChanged: () -> Unit = {}
) {

    private companion object {
        private const val TAG = "AuthRepository"
    }

    // ---------------------------------------------------------------
    // Auth round-trips
    // ---------------------------------------------------------------

    suspend fun login(email: String, password: String): Result<AuthStore.Session> =
        withContext(Dispatchers.IO) {
            val result = ApiClient.login(email.trim(), password)
                .map { it.toSession() }
            result.onSuccess {
                AuthStore.saveOrUpdate(context, it)
                onSessionChanged()
            }
            result
        }

    suspend fun register(email: String, password: String): Result<AuthStore.Session> =
        withContext(Dispatchers.IO) {
            val result = ApiClient.register(email.trim(), password)
                .map { it.toSession() }
            result.onSuccess {
                AuthStore.saveOrUpdate(context, it)
                onSessionChanged()
            }
            result
        }

    /**
     * V4.2 — sign in via Google. The caller obtains the ID token from
     * the Google Sign-In SDK and passes it here; this method forwards it
     * to the backend which does the actual verification and issues a
     * regular JWT for the mobile app to use.
     *
     * C3 — the optional [nonce] is the raw per-attempt replay-protection
     * value (the SHA-256 hash is what was sent to Google as the
     * `nonce` request param). Forwarded to the backend, which stores
     * it for 5 minutes and rejects any Google ID token whose `nonce`
     * claim doesn't match a still-valid entry. Null disables the
     * replay check on the server.
     */
    suspend fun googleLogin(idToken: String, nonce: String? = null): Result<AuthStore.Session> =
        withContext(Dispatchers.IO) {
            val result = ApiClient.googleLogin(idToken, nonce).map { it.toSession() }
            result.onSuccess {
                AuthStore.saveOrUpdate(context, it)
                onSessionChanged()
            }
            result
        }

    // ---------------------------------------------------------------
    // Multi-account queries
    // ---------------------------------------------------------------

    /** Currently-active session (token is already restored on ApiClient). */
    fun activeSession(): AuthStore.Session? = AuthStore.getActive(context)

    /** Convenience for callers that only need the user id. 0 when anonymous. */
    fun currentUserId(): Int = activeSession()?.userId ?: 0

    /** All saved sessions, most-recently-used first. */
    fun allSessions(): List<AuthStore.Session> = AuthStore.loadAll(context)

    /**
     * Switches the active session to the saved account with [email]. Returns
     * true on success, false if no such saved session exists.
     */
    fun switchAccount(email: String): Boolean {
        val ok = AuthStore.setActive(context, email)
        if (ok) onSessionChanged()
        return ok
    }

    /**
     * Removes the session with [email]. If it was the active one, the next
     * most-recent session becomes active (or none, if the list is empty).
     */
    fun removeAccount(email: String) {
        AuthStore.remove(context, email)
        onSessionChanged()
    }

    /**
     * C1 — signs out of the active account. Best-effort POSTs to
     * `/auth/logout` so the server can revoke the JWT, then clears
     * the *entire* local store (including other saved sessions):
     * saved accounts on disk carry tokens, so leaving them behind
     * after a logout would defeat the point of encrypting the store.
     * After this call the user is forced through the Google Sign-In
     * flow again.
     */
    suspend fun logoutActiveKeepSessions() = withContext(Dispatchers.IO) {
        try {
            ApiClient.logout()
        } catch (t: Throwable) {
            Logger.w(TAG, "Server logout failed (proceeding with local wipe)", t)
        }
        AuthStore.clearAll(context)
        onSessionChanged()
    }

    /**
     * Removes every saved session and clears the bearer token. Also
     * notifies the server so the tokens get revoked (best-effort).
     */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        try {
            ApiClient.logout()
        } catch (t: Throwable) {
            Logger.w(TAG, "Server logout failed (proceeding with local wipe)", t)
        }
        AuthStore.clearAll(context)
        onSessionChanged()
    }

    private fun AuthResponse.toSession() = AuthStore.Session(
        email = user.email,
        userId = user.id,
        token = token
    )
}