package com.example.notes.data.remote

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.example.notes.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * V4.4 — HTTP client rewritten on top of OkHttp + kotlinx.serialization.
 *
 * Replaces the V3.1 HttpURLConnection implementation. OkHttp gives us
 * connection pooling, HTTP/2, automatic gzip, and a sane interceptor
 * pipeline. kotlinx.serialization means we no longer hand-roll JSON.
 *
 * All public methods are suspend functions; the underlying network I/O
 * runs on [Dispatchers.IO].
 */
object ApiClient {

    /**
     * V5.0.2 — exposed as a [var] (not `const val`) so unit tests can
     * retarget the client at a [okhttp3.mockwebserver.MockWebServer].
     * The default is the production Cloudflare Worker URL.
     */
    var BASE_URL: String = "https://notes-backend.raximjonozod1727.workers.dev"

    private const val TAG = "ApiClient"

    /**
     * Currently active bearer token. Cleared on logout. Set automatically
     * by [com.example.notes.data.local.AuthStore] when a session is loaded
     * or saved.
     */
    @Volatile
    var authToken: String? = null

    /** Shared JSON instance with lenient parsing for unknown fields. */
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /**
     * V4.4 — single OkHttp instance for the whole process. Timeouts are
     * intentionally aggressive (15s) because notes syncing is best-effort
     * and the user can always retry manually.
     */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    // ---------------------------------------------------------------
    // Public API — auth
    // ---------------------------------------------------------------

    suspend fun register(email: String, password: String): Result<AuthResponse> {
        val body = jsonObjectBody(
            "email" to email,
            "password" to password
        )
        return execute("/auth/register", method = "POST", body = body, AuthResponse.serializer())
    }

    suspend fun login(email: String, password: String): Result<AuthResponse> {
        val body = jsonObjectBody(
            "email" to email,
            "password" to password
        )
        return execute("/auth/login", method = "POST", body = body, AuthResponse.serializer())
    }

    suspend fun me(): Result<UserResponse> =
        execute("/auth/me", method = "GET", body = null, UserResponse.serializer())

    /**
     * V4.2 — Google OAuth. Sends the ID token obtained from the Google
     * Sign-In SDK to the backend, which verifies it with Google and
     * returns the same JWT shape as [login] / [register].
     */
    suspend fun googleLogin(idToken: String): Result<AuthResponse> {
        val body = jsonObjectBody("id_token" to idToken)
        return execute("/auth/google", method = "POST", body = body, AuthResponse.serializer())
    }

    // ---------------------------------------------------------------
    // Public API — notes
    // ---------------------------------------------------------------

    suspend fun listNotes(since: Long? = null): Result<NotesListResponse> {
        val path = if (since != null) "/notes?since=$since" else "/notes"
        return execute(path, method = "GET", body = null, NotesListResponse.serializer())
    }

    /**
     * V5.0.2 — backend wraps the response as `{ note: { ... } }` so the
     * client (and other future callers) can evolve the response shape
     * without breaking the existing single-note callers. We unwrap it
     * before returning so the rest of the app keeps the simple
     * `Result<NoteSyncDto>` signature.
     *
     * Without this wrapper the client tried to read fields like `id` /
     * `title` directly off the envelope and threw
     * `MissingFieldException` for every push — which left the local
     * note's `serverId` as `null` and caused the next push to generate
     * a fresh UUID, leading to 7 copies of the same note on the server.
     */
    suspend fun upsertNote(note: NoteSyncDto): Result<NoteSyncDto> {
        val body = json.encodeToString(NoteSyncDto.serializer(), note)
        return execute("/notes", method = "POST", body = body, UpsertNoteResponse.serializer())
            .map { it.note }
    }

    suspend fun deleteNote(id: String): Result<Unit> = executeUnit("/notes/$id", method = "DELETE")

    // ---------------------------------------------------------------
    // Public API — uploads
    // ---------------------------------------------------------------

    /**
     * Reads image bytes from the given content URI, base64-encodes them and
     * POSTs them to `/upload`. Returns the [UploadResponse] from the server
     * which contains the public URL to use in image embeds.
     *
     * V5.1.4 — guaranteed logging for every failure mode so the user
     * doesn't hit a silent no-op. Earlier iterations caught
     * `Throwable` and just wrapped it; the resulting [Result.failure]
     * was easy to drop on the floor. We now split:
     *   1. URI read failure  → specific IOException
     *   2. Network / HTTP failure → existing OkHttp result
     *   3. Anything else  → Throwable caught
     */
    suspend fun uploadImage(uri: Uri, context: Context): Result<UploadResponse> =
        withContext(Dispatchers.IO) {
            val bytes = try {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (e: SecurityException) {
                Logger.w(TAG, "uploadImage: no permission to read $uri", e)
                return@withContext Result.failure<UploadResponse>(
                    IOException("Permission denied for URI: $uri")
                )
            } catch (e: Throwable) {
                Logger.w(TAG, "uploadImage: openInputStream threw for $uri", e)
                return@withContext Result.failure<UploadResponse>(
                    IOException("Unable to read image bytes: ${e.message ?: e.javaClass.simpleName}")
                )
            }
            if (bytes == null || bytes.isEmpty()) {
                Logger.w(TAG, "uploadImage: empty bytes from $uri")
                return@withContext Result.failure<UploadResponse>(
                    IOException("Empty bytes from URI: $uri")
                )
            }

            // Detect the actual MIME so the file isn't always called
            // .jpg — upload handler currently expects image/* but a
            // png stream sent with content_type=image/jpeg is just
            // polite dishonesty.
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            val ext = when {
                mime.endsWith("png") -> "png"
                mime.endsWith("webp") -> "webp"
                mime.endsWith("gif") -> "gif"
                mime.endsWith("heic") || mime.endsWith("heif") -> "heic"
                else -> "jpg"
            }

            try {
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val body = jsonObjectBody(
                    "filename" to "image.$ext",
                    "content_type" to mime,
                    "data" to base64
                )
                execute("/upload", method = "POST", body = body, UploadResponse.serializer())
            } catch (t: Throwable) {
                Logger.w(TAG, "uploadImage: HTTP layer failed for $uri", t)
                Result.failure(t)
            }
        }

    // ---------------------------------------------------------------
    // JSON encoding helpers
    // ---------------------------------------------------------------

    /** Build a small JSON object literal from string-keyed pairs. */
    private fun jsonObjectBody(vararg pairs: Pair<String, String>): String {
        val sb = StringBuilder("{")
        pairs.forEachIndexed { index, (k, v) ->
            if (index > 0) sb.append(',')
            sb.append(jsonString(k)).append(':').append(jsonString(v))
        }
        sb.append('}')
        return sb.toString()
    }

    /** Minimal JSON string encoder — sufficient for our limited inputs. */
    private fun jsonString(raw: String): String {
        val sb = StringBuilder("\"")
        for (ch in raw) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) {
                    sb.append("\\u%04x".format(ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    // ---------------------------------------------------------------
    // Generic HTTP helpers
    // ---------------------------------------------------------------

    private fun authHeaders(): Map<String, String> {
        val token = authToken ?: return emptyMap()
        return mapOf("Authorization" to "Bearer $token")
    }

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private fun buildRequest(path: String, method: String, body: String?): Request {
        val builder = Request.Builder().url(BASE_URL + path)
        authHeaders().forEach { (k, v) -> builder.header(k, v) }
        builder.header("Accept", "application/json")
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            "POST" -> builder.post((body ?: "").toRequestBody(JSON_MEDIA_TYPE))
            else -> throw IllegalArgumentException("Unsupported method: $method")
        }
        return builder.build()
    }

    /**
     * Low-level request executor. [body] is a raw JSON string (or null for
     * GET/DELETE). [serializer] describes the response DTO; passing it
     * explicitly avoids reflective lookup costs.
     */
    private suspend fun <T> execute(
        path: String,
        method: String,
        body: String?,
        serializer: KSerializer<T>
    ): Result<T> = withContext(Dispatchers.IO) {
        val request = try {
            buildRequest(path, method, body)
        } catch (t: Throwable) {
            Logger.w(TAG, "buildRequest $method $path failed", t)
            return@withContext Result.failure<T>(t)
        }

        try {
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    Logger.w(TAG, "$method $path failed: ${response.code} — $text")
                    return@withContext Result.failure<T>(
                        IOException("HTTP ${response.code}: $text")
                    )
                }
                if (text.isBlank()) {
                    return@withContext Result.failure<T>(IOException("Empty response body"))
                }
                try {
                    Result.success(json.decodeFromString(serializer, text))
                } catch (t: Throwable) {
                    Logger.w(TAG, "Failed to parse $method $path response: $text", t)
                    Result.failure<T>(t)
                }
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "$method $path threw", t)
            Result.failure(t)
        }
    }

    private suspend fun executeUnit(
        path: String,
        method: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val request = try {
            buildRequest(path, method, null)
        } catch (t: Throwable) {
            Logger.w(TAG, "buildRequest $method $path failed", t)
            return@withContext Result.failure<Unit>(t)
        }
        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    val msg = response.body?.string() ?: "HTTP ${response.code}"
                    Logger.w(TAG, "$method $path failed: ${response.code} — $msg")
                    Result.failure(IOException("HTTP ${response.code}: $msg"))
                }
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "$method $path threw", t)
            Result.failure(t)
        }
    }
}

/* -------------------------------------------------------------------------
 * Serializable DTOs
 *
 * These types are @Serializable so kotlinx.serialization can both encode
 * them (for the request body of upsertNote) and decode them from the
 * server's JSON responses. They are the only place in the app that
 * references kotlinx.serialization types — the rest of the codebase uses
 * [NoteEntity] for storage and never sees these classes directly.
 * ------------------------------------------------------------------------- */

@Serializable
data class AuthResponse(
    val token: String,
    val user: UserResponse
)

@Serializable
data class UserResponse(
    val id: Int,
    val email: String
)

@Serializable
data class NoteSyncDto(
    val id: String,
    val title: String,
    val content: String,
    val is_favorite: Int,
    val is_trashed: Int,
    val background_color: Int
)

@Serializable
data class NotesListResponse(
    val notes: List<NoteSyncDto>,
    val sync_timestamp: Long
)

/**
 * V5.0.2 — server-side response envelope for POST /notes.
 * Mirrors the shape used by [NotesListResponse] (top-level wrapper)
 * so the API stays consistent and is forward-compatible with future
 * fields like `server_version` or `etag`.
 */
@Serializable
data class UpsertNoteResponse(
    val note: NoteSyncDto
)

@Serializable
data class UploadResponse(
    val url: String,
    val r2_key: String,
    val content_type: String,
    val size: Int
)
