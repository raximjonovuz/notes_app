package com.example.notes.data.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.example.notes.util.Logger
import java.io.ByteArrayOutputStream
import kotlin.math.max
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

    /**
     * V5.2.2 — dedicated [OkHttpClient] used by the editor to render
     * image blocks whose URI is an http(s) URL (e.g. an R2 public URL
     * after a successful upload). Kept separate from the auth-aware
     * [httpClient] so image rendering never blocks on missing tokens
     * or extra interceptors. Slightly longer read timeout because
     * gallery images can be a few MB on a slow connection.
     */
    val imageClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
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

    /**
     * V5.3 — `since` is now a monotonic `change_id` (Long), not a
     * millisecond. Server returns the rows that touched the change_log
     * table since this cursor. The cursor is also returned in the
     * `sync_cursor` field of the response so the next pull is exactly
     * incremental.
     *
     * `since=null` triggers a "first sync" payload: server returns
     * every live row for the user (no tombstones).
     */
    suspend fun listNotes(since: Long? = null): Result<NotesListResponse> {
        val path = if (since != null) "/notes?since=$since" else "/notes"
        return execute(path, method = "GET", body = null, NotesListResponse.serializer())
    }

    /**
     * V5.3 — `updated_at` is the client's authoritative clock; the
     * server stores it as `client_updated_at` (BUG 5 fix). Caller is
     * responsible for sending the row's local `updatedAt` (or `now()`
     * for new notes).
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
     * V5.3 — BUG 7 fix. We used to read the entire image into RAM and
     * base64 it, which OOM'd on multi-MB phone photos and exceeded the
     * 8MB server-side cap. The new flow:
     *   1. Read the input via ContentResolver (max 16MB headroom).
     *   2. Decode to a Bitmap with `inSampleSize` so very large images
     *      don't allocate 4096-pixel-wide bitmaps.
     *   3. Re-encode to JPEG quality 85 into a memory buffer.
     *   4. If the resulting buffer exceeds 6 MB (we leave headroom
     *      under the 8 MB server cap), downscale further.
     *
     * Returns the [UploadResponse] from the server.
     */
    suspend fun uploadImage(uri: Uri, context: Context): Result<UploadResponse> =
        withContext(Dispatchers.IO) {
            val original = try {
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
            if (original == null || original.isEmpty()) {
                Logger.w(TAG, "uploadImage: empty bytes from $uri")
                return@withContext Result.failure<UploadResponse>(
                    IOException("Empty bytes from URI: $uri")
                )
            }

            val detected = com.example.notes.util.ImageSniffer.detectOrJpeg(original)
            val mime = detected.mime
            val ext = detected.ext

            val jpegBytes: ByteArray = try {
                compressToJpeg(original)
            } catch (t: Throwable) {
                Logger.w(TAG, "uploadImage: compress failed; falling back to raw bytes", t)
                original
            }

            try {
                val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
                val body = jsonObjectBody(
                    "filename" to "image.${detected.ext}",
                    "content_type" to detected.mime,
                    "data" to base64
                )
                execute("/upload", method = "POST", body = body, UploadResponse.serializer())
            } catch (t: Throwable) {
                Logger.w(TAG, "uploadImage: HTTP layer failed for $uri", t)
                Result.failure(t)
            }
        }

    /**
     * Decode the bytes into a Bitmap, scale to max 2048 px on the long
     * side, re-encode as JPEG q=85. If the result exceeds 6 MB, halve
     * dimensions and try again. Returns JPEG bytes.
     */
    private fun compressToJpeg(src: ByteArray): ByteArray {
        val maxBytes = 6 * 1024 * 1024
        val maxLongSide = 2048
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(src, 0, src.size, opts)
        val srcLong = maxOf(opts.outWidth, opts.outHeight)
        var sample = 1
        while (srcLong / (sample * 2) >= maxLongSide) sample *= 2

        var quality = 85
        var scaledSide = maxLongSide
        while (true) {
            val decode = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeByteArray(src, 0, src.size, decode)
                ?: throw IOException("BitmapFactory failed")
            val scaled = if (max(bmp.width, bmp.height) > scaledSide) {
                val k = scaledSide.toFloat() / max(bmp.width, bmp.height).toFloat()
                Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * k).toInt().coerceAtLeast(1),
                    (bmp.height * k).toInt().coerceAtLeast(1),
                    true
                )
            } else bmp
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            val bytes = baos.toByteArray()
            if (bytes.size <= maxBytes) {
                if (scaled !== bmp) bmp.recycle()
                scaled.recycle()
                return bytes
            }
            quality -= 15
            scaledSide = (scaledSide * 0.8f).toInt().coerceAtLeast(512)
            if (quality < 50) {
                if (scaled !== bmp) bmp.recycle()
                scaled.recycle()
                return bytes
            }
            if (scaled !== bmp) bmp.recycle()
            scaled.recycle()
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

/**
 * V5.3 — extends the previous DTO with the new server-side columns.
 * Optional / default-valued so the same struct can read older server
 * payloads during the rolling upgrade window.
 */
@Serializable
data class NoteSyncDto(
    val id: String,
    val title: String,
    val content: String,
    val is_favorite: Int = 0,
    val is_trashed: Int = 0,
    val background_color: Int = 0,
    val client_updated_at: Long = 0,
    val created_at: Long = 0,
    val change_id: Long = 0,
    val deleted_at: Long? = null
)

@Serializable
data class NotesListResponse(
    val notes: List<NoteSyncDto>,
    val sync_timestamp: Long,
    /** V5.3 — monotonic sync cursor (BUG 4). Replaces the wall-clock. */
    val sync_cursor: Long = 0
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
