package com.example.notes.ui.auth

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.notes.NotesApplication
import com.example.notes.data.local.AuthStore
import com.example.notes.data.remote.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * V4.2 — Google-only sign-in. The UI no longer surfaces an email/password
 * form, so the only entry point here is [submitGoogle] / [switchAccount] /
 * [removeAccount] / [clearGoogleError]. The [LoginScreen] owns the
 * `IntentSenderForResult` launcher (it has to be activity-scoped) and
 * just hands the resulting id token to [submitGoogle].
 */
data class AuthUiState(
    val errorMessage: String? = null,
    val isAuthenticated: Boolean = false,
    val sessionEmail: String? = null,
    val savedAccounts: List<AuthStore.Session> = emptyList(),
    val activeEmail: String? = null,
    val isGoogleLoading: Boolean = false,
    /**
     * C3 — per-attempt replay-protection nonce. The login screen
     * generates a random UUID, hashes it with SHA-256 (the value
     * forwarded to Google), and stashes the *raw* form here so
     * [submitGoogle] can send it to the backend for one-time
     * validation. Cleared on every attempt (success or failure).
     */
    val pendingNonce: String? = null
)

class AuthViewModel(
    private val repository: AuthRepository,
    private val onLoginSucceeded: () -> Unit = {}
) : ViewModel() {

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    /**
     * Guards against duplicate concurrent [submitGoogle] network calls while
     * allowing [beginGoogleAttempt] to set [AuthUiState.isGoogleLoading] = true
     * during the Credential Manager sheet phase without blocking the subsequent
     * [submitGoogle] invocation.
     */
    private var submitInFlight = false

    private fun initialState(): AuthUiState {
        val saved = repository.allSessions()
        val active = repository.activeSession()
        return AuthUiState(
            isAuthenticated = active != null,
            sessionEmail = active?.email,
            savedAccounts = saved,
            activeEmail = active?.email,
            isGoogleLoading = false
        )
    }

    fun switchAccount(email: String, onDone: (AuthStore.Session?) -> Unit = {}) {
        val ok = repository.switchAccount(email)
        if (!ok) {
            onDone(null)
            return
        }
        val active = repository.activeSession()
        _state.value = _state.value.copy(
            isAuthenticated = active != null,
            sessionEmail = active?.email,
            activeEmail = active?.email,
            savedAccounts = repository.allSessions()
        )
        onDone(active)
        if (active != null) {
            onLoginSucceeded()
        }
    }

    fun removeAccount(email: String) {
        repository.removeAccount(email)
        val saved = repository.allSessions()
        val active = repository.activeSession()
        _state.value = _state.value.copy(
            savedAccounts = saved,
            activeEmail = active?.email,
            sessionEmail = active?.email,
            isAuthenticated = active != null
        )
    }

    /**
     * V4.2 — Continue with Google. The [LoginScreen]'s launcher hands us
     * the id token, we forward it to the backend for verification + JWT
     * issuance, and update the active session on success.
     *
     * C3 — the optional [nonce] is the raw form of the value that was
     * SHA-256-hashed before being passed to Google as a `nonce` request
     * param. The backend stores the raw nonce for 5 minutes (D1
     * `auth_nonces` table) and rejects any Google ID token whose
     * `nonce` claim doesn't match a still-valid entry. This is a no-op
     * (null) when the deployment hasn't enabled nonce validation yet,
     * so old clients keep working.
     */
    fun submitGoogle(idToken: String, nonce: String? = _state.value.pendingNonce) {
        if (submitInFlight) return
        submitInFlight = true
        val s = _state.value
        _state.value = s.copy(isGoogleLoading = true, errorMessage = null)
        viewModelScope.launch {
            try {
                val result = repository.googleLogin(idToken, nonce)
                result.onSuccess { session ->
                    _state.value = _state.value.copy(
                        isGoogleLoading = false,
                        isAuthenticated = true,
                        sessionEmail = session.email,
                        savedAccounts = repository.allSessions(),
                        activeEmail = session.email,
                        pendingNonce = null
                    )
                    onLoginSucceeded()
                }.onFailure { err ->
                    _state.value = _state.value.copy(
                        isGoogleLoading = false,
                        errorMessage = err.localizedMessage ?: "Google sign-in failed.",
                        pendingNonce = null
                    )
                }
            } finally {
                submitInFlight = false
            }
        }
    }

    /**
     * Password-based login. Supported for backwards-compatibility and tests.
     */
    fun submitPassword(email: String, password: String, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val result = repository.login(email, password)
            result.onSuccess { session ->
                _state.value = _state.value.copy(
                    isAuthenticated = true,
                    sessionEmail = session.email,
                    savedAccounts = repository.allSessions(),
                    activeEmail = session.email
                )
                onLoginSucceeded()
                onDone(true)
            }.onFailure { err ->
                _state.value = _state.value.copy(
                    errorMessage = err.localizedMessage ?: "Login failed."
                )
                onDone(false)
            }
        }
    }

    /**
     * C3 — stash the per-attempt replay-protection nonce so that
     * [submitGoogle] can forward it to the backend. Called from the
     * LoginScreen right before launching Credential Manager.
     */
    fun setPendingNonce(rawNonce: String) {
        _state.value = _state.value.copy(pendingNonce = rawNonce)
    }

    /**
     * C3 — called the moment the user taps "Continue with Google".
     * Clears any previous error and disables double-tap (the button
     * also has its own `inFlight` guard, this is the ViewModel-side
     * mirror used by tests).
     */
    fun beginGoogleAttempt() {
        _state.value = _state.value.copy(errorMessage = null, isGoogleLoading = true)
    }

    fun clearGoogleError() {
        submitInFlight = false
        _state.value = _state.value.copy(errorMessage = null, isGoogleLoading = false, pendingNonce = null)
    }

    /**
     * C3 — show a specific, human-readable error string from the
     * Credential Manager flow (e.g. "No Google account on device").
     */
    fun setGoogleError(message: String) {
        submitInFlight = false
        _state.value = _state.value.copy(
            errorMessage = message,
            isGoogleLoading = false,
            pendingNonce = null
        )
    }

    /**
     * C1 — signs out of the active account, fire-and-forget. The
     * server POST and the local store wipe happen on the IO
     * dispatcher (suspend repository methods); we update the UI
     * synchronously so the next screen shows the logged-out state
     * without waiting for the network round-trip.
     */
    fun logoutActive() {
        if (repository.activeSession() == null) return
        viewModelScope.launch {
            repository.logoutActiveKeepSessions()
        }
        _state.value = _state.value.copy(
            savedAccounts = emptyList(),
            activeEmail = null,
            sessionEmail = null,
            isAuthenticated = false
        )
    }

    /** C1 — wipes every saved session; best-effort server revocation. */
    fun logoutAll() {
        viewModelScope.launch {
            repository.clearAll()
        }
        _state.value = _state.value.copy(
            savedAccounts = emptyList(),
            activeEmail = null,
            sessionEmail = null,
            isAuthenticated = false
        )
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val app = application as? NotesApplication
                    AuthViewModel(
                        repository = AuthRepository(
                            context = application,
                            onSessionChanged = { app?.repository?.refreshActiveUser() }
                        ),
                        onLoginSucceeded = { app?.onLoginSucceeded() }
                    )
                }
            }
    }
}
