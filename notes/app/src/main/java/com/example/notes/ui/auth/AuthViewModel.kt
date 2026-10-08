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
    val isGoogleLoading: Boolean = false
)

class AuthViewModel(
    private val repository: AuthRepository
) : ViewModel() {

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

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
     */
    fun submitGoogle(idToken: String) {
        val s = _state.value
        if (s.isGoogleLoading) return
        _state.value = s.copy(isGoogleLoading = true, errorMessage = null)
        viewModelScope.launch {
            val result = repository.googleLogin(idToken)
            result.onSuccess { session ->
                _state.value = _state.value.copy(
                    isGoogleLoading = false,
                    isAuthenticated = true,
                    sessionEmail = session.email,
                    savedAccounts = repository.allSessions(),
                    activeEmail = session.email
                )
            }.onFailure { err ->
                _state.value = _state.value.copy(
                    isGoogleLoading = false,
                    errorMessage = err.localizedMessage ?: "Google sign-in failed."
                )
            }
        }
    }

    fun clearGoogleError() {
        _state.value = _state.value.copy(errorMessage = null, isGoogleLoading = false)
    }

    /**
     * Clears the active session pointer but keeps the saved account so
     * the user can re-pick it from the Login screen without re-authing
     * with Google. Mirrors the V3.2 behavior.
     */
    fun logoutActive() {
        if (repository.activeSession() == null) return
        repository.logoutActiveKeepSessions()
        val saved = repository.allSessions()
        _state.value = _state.value.copy(
            savedAccounts = saved,
            activeEmail = null,
            sessionEmail = null,
            isAuthenticated = false
        )
    }

    /** Wipes every saved session. */
    fun logoutAll() {
        repository.clearAll()
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
                    val app = application as NotesApplication
                    AuthViewModel(
                        AuthRepository(
                            context = app,
                            onSessionChanged = { app.repository.refreshActiveUser() }
                        )
                    )
                }
            }
    }
}
