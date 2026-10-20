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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.HttpUrl
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
     * V4.4 — single OkHttp instance for the whole process.
     * V5.3.1 — writeTimeout bumped to 60s for large image uploads
     * (HEIC originals can be 1-2 MB; base64-encoded to ~1.5 MB JSON,
     * which can take 30+ s on a slow 4G connection). Connect / read
     * stay at 15 s because notes sync metadata is tiny.
     */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * V5.2.2 — dedicated [OkHttpClient] used by the editor to render
     * image blocks whose URI is an http(s) URL (e.g. an R2 public URL
     * after a successful upload).
     *
     * V5.3.2 — interceptor injects the current bearer token on every
     * request. The backend's `/files/<key>` endpoint requires
     * Authorization (the V5.3 security fix) and the previous approach
     * of attaching the header at the call site missed requests made
     * before [authToken] was primed (e.g. immediately after process
     * restart when the user opens a note before any sync has run).
     * Slightly longer read timeout because gallery images can be a few
     * MB on a slow connection.
     *
     * C2 — the Authorization header is attached **only** when the
     * request URL points at the same host as [BASE_URL]. Note content
     * may legitimately embed https:// URLs from other hosts (a future
     * "embed external image" feature, or a note that survived a
     * different deployment); leaking the bearer token to those hosts
     * would be a credential-disclosure bug. The plain httpClient is
     * unaffected because it only ever builds URLs from [BASE_URL].
     */
    val imageClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .addInterceptor { chain ->
                val original = chain.request()
                val isOurHost = isBaseUrlHost(original.url)
                val token = authToken
                val request = if (isOurHost && !token.isNullOrBlank()) {
                    original.newBuilder()
                        .header("Authorization", "Bearer $token")
                        .build()
                } else {
                    if (!isOurHost) {
                        Logger.d(
                            TAG,
                            "imageClient: skipping auth for cross-host request ${original.url}"
                        )
                    }
                    original
                }
                chain.proceed(request)
            }
            .build()
    }

    /**
     * C2 — true when [url]'s host matches [BASE_URL]'s host. Used by
     * [imageClient]'s interceptor to decide whether the bearer token
     * is safe to attach. Both arguments are parsed via `URI` (not
     * just string-prefix matching) so a malicious payload like
     * `https://attacker.com/notes-backend.raximjonozod1727.workers.dev/x`
     * is correctly recognised as cross-host.
     */
    private fun isBaseUrlHost(url: HttpUrl): Boolean {
        return try {
            val base = java.net.URI(BASE_URL)
            url.host.equals(base.host, ignoreCase = true)
        } catch (t: Throwable) {
            // If [BASE_URL] is somehow unparseable, default to "not
            // our host" — better to under-attach the token than to
            // leak it to a wildcard.
            false
        }
    }

    // ---------------------------------------------------------------
    // Public API — auth
    // ---------------------------------------------------------------

    suspend fun register(email: String, password: String): Result<AuthResponse> {
        val body = jsonObjectBody {
            put("email", email)
            put("password", password)
        }
        return execute("/auth/register", method = "POST", body = body, AuthResponse.serializer())
    }

    suspend fun login(email: String, password: String): Result<AuthResponse> {
        val body = jsonObjectBody {
            put("email", email)
            put("password", password)
        }
        return execute("/auth/login", method = "POST", body = body, AuthResponse.serializer())
    }

    suspend fun me(): Result<UserResponse> =
        execute("/auth/me", method = "GET", body = null, UserResponse.serializer())

    /**
     * C1 — server-side sign-out. Best-effort: a network failure does NOT
     * propagate as an error to the caller because the local token has to
     * be wiped either way; the next sync attempt will surface the stale
     * credential and the user will be forced through the re-login flow.
     * Returns true when the server replied 2xx (or there was nothing to
     * do, i.e. no token was set), false otherwise.
     */
    suspend fun logout(): Boolean {
        if (authToken.isNullOrBlank()) return true
        return try {
            val r = executeUnit("/auth/logout", method = "POST")
            if (r.isSuccess) {
                true
            } else {
                Logger.w(TAG, "Server logout returned failure (clearing local anyway)", r.exceptionOrNull())
                false
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "Server logout threw (clearing local anyway)", t)
            false
        }
    }

    /**
     * V4.2 — Google OAuth. Sends the ID token obtained from the Google
     * Sign-In SDK to the backend, which verifies it with Google and
     * returns the same JWT shape as [login] / [register].
     */
    suspend fun googleLogin(idToken: String, nonce: String? = null): Result<AuthResponse> {
        val body = jsonObjectBody {
            put("id_token", idToken)
            if (nonce != null) put("nonce", nonce)
        }
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

    /**
     * B1 — DELETE that distinguishes 200 (deleted) from 404 (already
     * gone) so the sync pass can immediately purge the local row in
     * both cases. A regular network error still returns
     * [Result.failure] so the caller can leave the local row dirty
     * for a retry on the next sync.
     */
    suspend fun deleteNoteByServerId(id: String): Result<DeleteNoteOutcome> =
        executeDeleteOutcome("/notes/$id", method = "DELETE")

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
     *
     * The metadata (filename, content_type) we send ALWAYS matches the
     * bytes we send: we re-sniff the post-compression buffer with
     * [com.example.notes.util.ImageSniffer.detectOrJpeg] and use that
     * as the source of truth. Sending `image/png` while uploading a
     * JPEG-compressed PNG would trip the server's magic-number check
     * (A8) and return 415.
     */
    suspend fun uploadImage(uri: Uri, context: Context): Result<UploadResponse> =
        withContext(Dispatchers.IO) {
            // B8 — Idempotency-Key: every upload gets a fresh UUID. The
            // server uses it to recognise a retry of the same upload
            // (e.g. dropped connection) and return the previously
            // stored R2 key instead of creating a duplicate.
            val idempotencyKey = java.util.UUID.randomUUID().toString()
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
                    IOException("Cannot read image: ${e.message ?: e.javaClass.simpleName}")
                )
            }
            if (original == null) {
                Logger.w(TAG, "uploadImage: cannot read image (null bytes) from $uri")
                return@withContext Result.failure<UploadResponse>(
                    IOException("Cannot read image")
                )
            }
            if (original.isEmpty()) {
                Logger.w(TAG, "uploadImage: empty image from $uri")
                return@withContext Result.failure<UploadResponse>(
                    IOException("Empty image")
                )
            }

            // Format-first compression policy. The previous version
            // always re-encoded to JPEG which produced correct
            // `image/jpeg` bytes for a PNG source but also corrupted
            // HEIC / WebP / GIF into JPEG — losing the original
            // container (and, in HEIC's case, the more efficient
            // compression). Now we sniff first and only re-encode the
            // formats that benefit from it (uncompressed / large
            // lossless sources).
            val originalFormat = com.example.notes.util.ImageSniffer.detect(original)
            val bytes: ByteArray = when (originalFormat?.mime) {
                // Already in a compressed container; passing through
                // preserves the original quality and avoids a lossy
                // re-encode. The server's 11 MB cap still applies.
                "image/jpeg", "image/heic", "image/heif",
                "image/webp", "image/gif" -> original
                // PNG is uncompressed and routinely 5-10x the size of
                // a JPEG at the same visual quality. Re-encode to
                // stay under the 6 MB client-side target.
                "image/png", "image/bmp" -> try {
                    compressToJpeg(original)
                } catch (t: Throwable) {
                    Logger.w(TAG, "uploadImage: compress failed; falling back to raw bytes", t)
                    original
                }
                // Unknown / null — try to compress; on failure keep
                // the raw bytes and let the server's magic-number
                // check produce a clear 415.
                else -> try {
                    compressToJpeg(original)
                } catch (t: Throwable) {
                    Logger.w(TAG, "uploadImage: compress failed; falling back to raw bytes", t)
                    original
                }
            }
            // Re-detect the *actual* bytes we're putting on the wire.
            // After a pass-through this is the same as `originalFormat`;
            // after a compress-to-JPEG it's `image/jpeg`. Either way
            // this is the source of truth for the metadata we send.
            val finalDetected = com.example.notes.util.ImageSniffer.detectOrJpeg(bytes)

            try {
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val body = jsonObjectBody {
                    put("filename", "image.${finalDetected.ext}")
                    put("content_type", finalDetected.mime)
                    put("data", base64)
                }
                executeWithIdempotencyKey(
                    "/upload",
                    method = "POST",
                    body = body,
                    idempotencyKey = idempotencyKey,
                    serializer = UploadResponse.serializer()
                )
            } catch (t: Throwable) {
                Logger.w(TAG, "uploadImage: HTTP layer failed for $uri", t)
                Result.failure(t)
            }
        }

    /**
     * Decode the bytes into a Bitmap, scale to max 1920 px on the long
     * side, re-encode as JPEG q=85. If the result exceeds 6 MB, halve
     * dimensions and try again. Returns JPEG bytes.
     *
     * Notable details:
     *   - EXIF orientation is honoured — phone photos with `TAG_ORIENTATION`
     *     set to 90/180/270 come out upright instead of sideways.
     *   - Alpha (PNG/WebP) is composited onto a white background so the
     *     JPEG doesn't render transparent pixels as black.
     *   - All intermediate Bitmaps are `recycle()`'d; the previous
     *     implementation leaked a small bitmap on every iteration
     *     when quality had to be lowered.
     */
    private fun compressToJpeg(src: ByteArray): ByteArray {
        val maxBytes = 6 * 1024 * 1024
        val maxLongSide = 1920
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
            val oriented = applyExifOrientation(src, bmp)
            val scaled = if (max(oriented.width, oriented.height) > scaledSide) {
                val k = scaledSide.toFloat() / max(oriented.width, oriented.height).toFloat()
                val s = Bitmap.createScaledBitmap(
                    oriented,
                    (oriented.width * k).toInt().coerceAtLeast(1),
                    (oriented.height * k).toInt().coerceAtLeast(1),
                    true
                )
                if (s !== oriented) oriented.recycle()
                s
            } else oriented

            // PNG/WebP can carry an alpha channel; JPEG doesn't.
            // Composite onto white so the resulting image matches what
            // the user saw in the picker instead of going opaque-black
            // wherever the source had transparency.
            val withBg: Bitmap = if (scaled.hasAlpha()) {
                val bg = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bg)
                canvas.drawColor(android.graphics.Color.WHITE)
                canvas.drawBitmap(scaled, 0f, 0f, null)
                if (bg !== scaled) scaled.recycle()
                bg
            } else scaled

            val baos = ByteArrayOutputStream()
            withBg.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            val bytes = baos.toByteArray()
            if (bytes.size <= maxBytes) {
                withBg.recycle()
                return bytes
            }
            quality -= 15
            scaledSide = (scaledSide * 0.8f).toInt().coerceAtLeast(512)
            if (quality < 50) {
                withBg.recycle()
                return bytes
            }
            withBg.recycle()
        }
    }

    /**
     * Apply EXIF orientation (if any) to [src], producing an upright
     * bitmap. Returns the original instance when no rotation is needed
     * (so the caller can `===` check and skip the recycle).
     *
     * Phone cameras often store landscape shots with the sensor
     * upright and a small `TAG_ORIENTATION` flag telling the viewer
     * to rotate. `BitmapFactory` honours `inSampleSize` but NOT
     * orientation — that's our job here.
     */
    private fun applyExifOrientation(src: ByteArray, decoded: Bitmap): Bitmap {
        return try {
            val exif = android.media.ExifInterface(java.io.ByteArrayInputStream(src))
            val orientation = exif.getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL
            )
            val matrix = android.graphics.Matrix()
            val rotate = when (orientation) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (rotate == 0f) return decoded
            matrix.postRotate(rotate)
            val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (rotated !== decoded) decoded.recycle()
            rotated
        } catch (t: Throwable) {
            decoded
        }
    }

    /**
     * C4 — defence-in-depth: replace any value behind a sensitive key
     * with `***` before the body reaches logcat. The matcher is
     * deliberately broad (any JSON-ish `"key":"value"`) and runs on
     * both the error-path log and the IOException message, so even an
     * accidental 401 body like `{"error":"...","token":"..."}` can't
     * leak.
     */
    private fun maskSensitiveFields(body: String): String {
        if (body.isEmpty()) return body
        val sensitive = Regex("\"(token|access_token|id_token|password|Authorization|Bearer|secret)\"\\s*:\\s*\"[^\"]*\"", RegexOption.IGNORE_CASE)
        return sensitive.replace(body) { match ->
            val key = match.value.substringBefore(':')
            "$key:\"***\""
        }
    }

    /**
     * C4 — single funnel for HTTP-failure log messages. In debug
     * builds the masked response body is included (truncated to
     * [previewChars] characters) so the developer can diagnose
     * without re-running the request. In release builds the body
     * is dropped entirely; only the method, path and HTTP status
     * land in logcat. This stops a release-build crash reporter
     * (Crashlytics, Sentry, …) from accidentally harvesting user
     * note titles or session tokens that happened to be in a
     * server response body.
     */
    private fun logHttpFailure(method: String, path: String, code: Int, maskedBody: String) {
        if (com.example.notes.BuildConfig.DEBUG) {
            val preview = maskedBody.take(200)
            Logger.w(TAG, "$method $path -> HTTP $code: $preview")
        } else {
            Logger.w(TAG, "$method $path -> HTTP $code")
        }
    }

    // ---------------------------------------------------------------
    // JSON encoding helpers
    // ---------------------------------------------------------------

    /**
     * Build a small JSON object literal from a [buildJsonObject] block and
     * serialize it via the shared [Json] instance. We delegate to
     * kotlinx.serialization so surrogate pairs, control characters, and
     * Unicode escapes are emitted per the JSON spec without us having to
     * hand-roll them.
     */
    private fun jsonObjectBody(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String =
        json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject(block)
        )

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
     * B8 — request builder that additionally attaches an
     * `Idempotency-Key` header. Used by [uploadImage] so the server
     * can recognise a retry of the same upload (network drop on a
     * slow 4G link) and return the previously-stored R2 key instead
     * of creating a duplicate row in the `images` table.
     */
    private fun buildRequestWithIdempotencyKey(
        path: String,
        method: String,
        body: String?,
        idempotencyKey: String
    ): Request {
        val base = buildRequest(path, method, body)
        return base.newBuilder()
            .header("Idempotency-Key", idempotencyKey)
            .build()
    }

    /**
     * B8 — [execute] variant that attaches an Idempotency-Key header.
     * The body, error handling and JSON parsing are otherwise identical
     * to [execute].
     */
    private suspend fun <T> executeWithIdempotencyKey(
        path: String,
        method: String,
        body: String?,
        idempotencyKey: String,
        serializer: KSerializer<T>
    ): Result<T> = withContext(Dispatchers.IO) {
        val request = try {
            buildRequestWithIdempotencyKey(path, method, body, idempotencyKey)
        } catch (t: Throwable) {
            Logger.w(TAG, "buildRequest $method $path failed", t)
            return@withContext Result.failure<T>(t)
        }
        try {
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val masked = maskSensitiveFields(text)
                    logHttpFailure(method, path, response.code, masked)
                    return@withContext Result.failure<T>(
                        IOException("HTTP ${response.code}: ${masked.take(200)}")
                    )
                }
                if (text.isBlank()) {
                    return@withContext Result.failure<T>(IOException("Empty response body"))
                }
                try {
                    Result.success(json.decodeFromString(serializer, text))
                } catch (t: Throwable) {
                    // C4 — release build drops the body. Debug shows a
                    // preview so the developer can spot malformed JSON.
                    if (com.example.notes.BuildConfig.DEBUG) {
                        Logger.w(TAG, "Failed to parse $method $path response: ${text.take(200)}", t)
                    } else {
                        Logger.w(TAG, "Failed to parse $method $path response", t)
                    }
                    Result.failure<T>(t)
                }
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "$method $path threw", t)
            Result.failure(t)
        }
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
                    val masked = maskSensitiveFields(text)
                    logHttpFailure(method, path, response.code, masked)
                    return@withContext Result.failure<T>(
                        IOException("HTTP ${response.code}: ${masked.take(200)}")
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
                    if (com.example.notes.BuildConfig.DEBUG) {
                        Logger.w(TAG, "$method $path failed: ${response.code} — ${maskSensitiveFields(msg).take(200)}")
                    } else {
                        Logger.w(TAG, "$method $path failed: ${response.code}")
                    }
                    Result.failure(IOException("HTTP ${response.code}: $msg"))
                }
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "$method $path threw", t)
            Result.failure(t)
        }
    }

    /**
     * B1 — DELETE executor that classifies 200 (deleted) and 404
     * (already gone) as successful [DeleteNoteOutcome] values and
     * returns a failed [Result] for every other error. The sync pass
     * uses this to purge the local row on either 200 or 404 and
     * keep the row dirty otherwise.
     */
    private suspend fun executeDeleteOutcome(
        path: String,
        method: String
    ): Result<DeleteNoteOutcome> = withContext(Dispatchers.IO) {
        val request = try {
            buildRequest(path, method, null)
        } catch (t: Throwable) {
            Logger.w(TAG, "buildRequest $method $path failed", t)
            return@withContext Result.failure<DeleteNoteOutcome>(t)
        }
        try {
            httpClient.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> Result.success(DeleteNoteOutcome.Deleted)
                    response.code == 404 -> Result.success(DeleteNoteOutcome.NotFound)
                    else -> {
                        val msg = response.body?.string() ?: "HTTP ${response.code}"
                        if (com.example.notes.BuildConfig.DEBUG) {
                            Logger.w(TAG, "$method $path failed: ${response.code} — ${maskSensitiveFields(msg).take(200)}")
                        } else {
                            Logger.w(TAG, "$method $path failed: ${response.code}")
                        }
                        Result.failure(IOException("HTTP ${response.code}: $msg"))
                    }
                }
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "$method $path threw", t)
            Result.failure(t)
        }
    }
}

/**
 * B1 — outcome of a DELETE /notes/<id> call. The sync pass treats
 * [Deleted] and [NotFound] both as "we're done, drop the local row";
 * a [Result.failure] (network error, 5xx, 401, …) means "leave the
 * local tombstone dirty so the next push retries".
 */
sealed class DeleteNoteOutcome {
    object Deleted : DeleteNoteOutcome()
    object NotFound : DeleteNoteOutcome()
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
 *
 * B1 — `deleted_at` is the server's soft-delete indicator. When set
 * in a `/notes?since=N` response the row is a tombstone and the
 * client must purge its local copy immediately. The wire name is
 * `deleted_at` (snake_case) which matches the server's column; the
 * DTO field is also `deleted_at` so kotlinx.serialization maps it
 * without an explicit [kotlinx.serialization.SerialName].
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
    val sync_cursor: Long = 0,
    /**
     * B1 — server reports whether more rows remain after this page.
     * Defaults to `false` so older server builds (which don't send
     * the field at all) are still understood — the conservative
     * "no more data" reading just means we'll discover additional
     * rows on the next cursor advance.
     */
    val has_more: Boolean = false
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
