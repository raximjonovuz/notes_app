package com.example.notes.ui.auth

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.notes.TestNotesApplication
import com.example.notes.data.local.AuthStore
import com.example.notes.data.remote.ApiClient
import com.example.notes.data.remote.AuthRepository
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = TestNotesApplication::class)
class AuthViewModelTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var repository: AuthRepository
    private val originalBaseUrl = ApiClient.BASE_URL
    private val originalToken = ApiClient.authToken

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AuthStore.clearAll(context)
        server = MockWebServer()
        server.start()
        ApiClient.BASE_URL = server.url("/").toString().trimEnd('/')
        ApiClient.authToken = null
        repository = AuthRepository(context)
    }

    @After
    fun tearDown() {
        server.shutdown()
        AuthStore.clearAll(context)
        ApiClient.BASE_URL = originalBaseUrl
        ApiClient.authToken = originalToken
    }

    private fun awaitLoadingComplete(viewModel: AuthViewModel, timeoutMs: Long = 5_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (viewModel.state.value.isGoogleLoading && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `beginGoogleAttempt then submitGoogle invokes googleLogin and sets isAuthenticated on success`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "token": "jwt-from-google",
                      "user": { "id": 42, "email": "user@example.com" }
                    }
                    """.trimIndent()
                )
        )

        val viewModel = AuthViewModel(repository)
        viewModel.beginGoogleAttempt()
        assertTrue(viewModel.state.value.isGoogleLoading)
        viewModel.setPendingNonce("raw-nonce-123")

        viewModel.submitGoogle("google-id-token-xyz")
        awaitLoadingComplete(viewModel)

        val recorded = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull("Expected /auth/google request to be sent after beginGoogleAttempt()", recorded)
        assertEquals("/auth/google", recorded!!.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"id_token\":\"google-id-token-xyz\""))
        assertTrue(body.contains("\"nonce\":\"raw-nonce-123\""))

        val finalState = viewModel.state.value
        assertFalse(finalState.isGoogleLoading)
        assertTrue(finalState.isAuthenticated)
        assertEquals("user@example.com", finalState.sessionEmail)
        assertNull(finalState.errorMessage)
        assertNull(finalState.pendingNonce)
    }

    @Test
    fun `beginGoogleAttempt then submitGoogle populates errorMessage and clears loading on failure`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"Invalid Google token"}""")
        )

        val viewModel = AuthViewModel(repository)
        viewModel.beginGoogleAttempt()
        assertTrue(viewModel.state.value.isGoogleLoading)

        viewModel.submitGoogle("bad-google-id-token", "nonce-456")
        awaitLoadingComplete(viewModel)

        val recorded = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull("Expected /auth/google request to be sent after beginGoogleAttempt()", recorded)
        assertEquals("/auth/google", recorded!!.path)

        val finalState = viewModel.state.value
        assertFalse(finalState.isGoogleLoading)
        assertFalse(finalState.isAuthenticated)
        assertNotNull(finalState.errorMessage)
        assertTrue(finalState.errorMessage!!.contains("Invalid Google token"))
        assertNull(finalState.pendingNonce)
    }

    @Test
    fun `successful submitGoogle invokes onLoginSucceeded callback`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "token": "jwt-google-sync",
                      "user": { "id": 101, "email": "syncuser@example.com" }
                    }
                    """.trimIndent()
                )
        )

        var loginSucceededCalled = false
        val viewModel = AuthViewModel(
            repository = repository,
            onLoginSucceeded = { loginSucceededCalled = true }
        )
        viewModel.beginGoogleAttempt()
        viewModel.submitGoogle("valid-token")
        awaitLoadingComplete(viewModel)

        assertTrue("onLoginSucceeded must be called on successful Google login", loginSucceededCalled)
        assertTrue(viewModel.state.value.isAuthenticated)
    }

    @Test
    fun `failed submitGoogle does not invoke onLoginSucceeded callback`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"Invalid token"}""")
        )

        var loginSucceededCalled = false
        val viewModel = AuthViewModel(
            repository = repository,
            onLoginSucceeded = { loginSucceededCalled = true }
        )
        viewModel.beginGoogleAttempt()
        viewModel.submitGoogle("invalid-token")
        awaitLoadingComplete(viewModel)

        assertFalse("onLoginSucceeded must NOT be called on failed Google login", loginSucceededCalled)
        assertFalse(viewModel.state.value.isAuthenticated)
    }

    @Test
    fun `successful switchAccount invokes onLoginSucceeded callback`() {
        AuthStore.saveOrUpdate(
            context,
            AuthStore.Session(email = "second@example.com", userId = 202, token = "jwt-202")
        )

        var loginSucceededCalled = false
        val viewModel = AuthViewModel(
            repository = repository,
            onLoginSucceeded = { loginSucceededCalled = true }
        )

        viewModel.switchAccount("second@example.com")

        assertTrue("onLoginSucceeded must be called when switching accounts", loginSucceededCalled)
        assertEquals("second@example.com", viewModel.state.value.activeEmail)
    }

    @Test
    fun `failed switchAccount does not invoke onLoginSucceeded callback`() {
        var loginSucceededCalled = false
        val viewModel = AuthViewModel(
            repository = repository,
            onLoginSucceeded = { loginSucceededCalled = true }
        )

        viewModel.switchAccount("nonexistent@example.com")

        assertFalse("onLoginSucceeded must NOT be called if account does not exist", loginSucceededCalled)
    }

    @Test
    fun `successful submitPassword invokes onLoginSucceeded callback`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "token": "jwt-password-sync",
                      "user": { "id": 303, "email": "pw@example.com" }
                    }
                    """.trimIndent()
                )
        )

        var loginSucceededCalled = false
        val viewModel = AuthViewModel(
            repository = repository,
            onLoginSucceeded = { loginSucceededCalled = true }
        )

        viewModel.submitPassword("pw@example.com", "secret123")

        val deadline = System.currentTimeMillis() + 3000L
        while (!loginSucceededCalled && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }

        assertTrue("onLoginSucceeded must be called on successful password login", loginSucceededCalled)
        assertTrue(viewModel.state.value.isAuthenticated)
        assertEquals("pw@example.com", viewModel.state.value.sessionEmail)
    }
}
