package com.example.notes.ui.util

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.example.notes.data.remote.ApiClient
import com.example.notes.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * UI-thread-free image loader used by [com.example.notes.ui.edit.NoteEditScreen]
 * and [com.example.notes.ui.notes.NotesListScreen]. Replaces the V5.2.2
 * `remember { … }` variant that ran [BitmapFactory.decodeStream] and the
 * OkHttp HTTP fetch directly on the main thread.
 *
 * Why it exists
 * -------------
 * The "image disappears after sync" report looked, on the surface, like a
 * sync / DB bug. The actual root cause was different: after a successful
 * upload the editor rewrote the block's `file://…` URI with the public
 * `https://…` R2 URL, then the next recomposition had to re-decode the
 * bitmap. `loadImageBitmapFromUri` did that synchronously inside
 * `remember(uriString) { … }`, so an HTTP fetch on a slow connection
 * would block the main thread for the duration of the read timeout and
 * the screen would render the placeholder while the user was waiting —
 * looking identical to "the image vanished after sync". Worse, when the
 * thread blocking timed out the bitmap came back null and the placeholder
 * stuck around forever.
 *
 * This helper moves the work to [Dispatchers.IO] via [LaunchedEffect]
 * so the UI stays responsive and a failed load returns null cleanly
 * instead of holding the frame hostage. It logs every failure so the
 * reason is recoverable from `adb logcat`.
 */
@Composable
fun rememberLoadedImage(uriString: String): ImageBitmap? {
    val context = LocalContext.current
    var bitmap by remember(uriString) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uriString) {
        bitmap = withContext(Dispatchers.IO) {
            loadImageBitmapFromUri(context, uriString)
        }
    }
    return bitmap
}

/**
 * Decode the bytes that [uriString] points at into a Compose
 * [ImageBitmap]. Runs on the caller's thread — call sites should already
 * be inside `withContext(Dispatchers.IO) { … }` (see [rememberLoadedImage]).
 *
 * `content://` and `file://` URIs go through the content resolver;
 * `http(s)` URIs go through OkHttp. All paths return null on failure
 * (no exception ever escapes) so the UI can decide how to react.
 */
private fun loadImageBitmapFromUri(
    context: Context,
    uriString: String
): ImageBitmap? {
    return try {
        val uri = uriString.toUri()
        val input = when (uri.scheme) {
            "http", "https" -> {
                val request = okhttp3.Request.Builder()
                    .url(uriString)
                    .apply {
                        ApiClient.authToken?.let { token ->
                            header("Authorization", "Bearer $token")
                        }
                    }
                    .build()
                ApiClient.imageClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) null else resp.body?.byteStream()
                }
            }
            else -> context.contentResolver.openInputStream(uri)
        }
        input?.use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
    } catch (e: Throwable) {
        Logger.w(
            "NoteEditScreen",
            "loadImageBitmapFromUri failed for $uriString: ${e.message ?: e.javaClass.simpleName}",
            e
        )
        null
    }
}
