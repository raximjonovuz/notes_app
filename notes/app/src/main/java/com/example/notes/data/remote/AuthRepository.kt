package com.example.notes.data.remote

import android.content.Context
import com.example.notes.data.local.AuthStore
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
     */
    suspend fun googleLogin(idToken: String): Result<AuthStore.Session> =
        withContext(Dispatchers.IO) {
            val result = ApiClient.googleLogin(idToken).map { it.toSession() }
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
     * Signs out of the active account *without* removing its saved
     * session — the user can re-sign-in later from the Login screen.
     */
    fun logoutActiveKeepSessions() {
        AuthStore.clearActive(context)
        onSessionChanged()
    }

    /** Removes every saved session and clears the bearer token. */
    fun clearAll() {
        AuthStore.clearAll(context)
        onSessionChanged()
    }

    // ---------------------------------------------------------------
    // Backwards-compat
    // ---------------------------------------------------------------

    /** @deprecated use [activeSession]. */
    @Deprecated("Use activeSession() instead.")
    fun currentSession(): AuthStore.Session? = activeSession()

    /** @deprecated use [clearAll]. */
    @Deprecated("Use clearAll() instead.")
    fun logout() = clearAll()

    private fun AuthResponse.toSession() = AuthStore.Session(
        email = user.email,
        userId = user.id,
        token = token
    )
}