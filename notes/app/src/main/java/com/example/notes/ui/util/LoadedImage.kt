package com.example.notes.ui.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent disk cache for remote and local images.
 * Keeps downloaded HTTP images in `cacheDir/images_cache/` so they survive
 * app restarts, process death, and remain visible even in offline mode.
 */
object ImageDiskCache {
    private fun getCacheDir(context: Context): File {
        val dir = File(context.cacheDir, "images_cache")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun hashUrl(url: String): String {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            digest.update(url.toByteArray(Charsets.UTF_8))
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Throwable) {
            url.hashCode().toString()
        }
    }

    fun getCacheFile(context: Context, url: String): File {
        val hash = hashUrl(url)
        val ext = url.substringAfterLast('.', "jpg").take(4).let {
            if (it.matches(Regex("[a-zA-Z0-9]+"))) it else "jpg"
        }
        return File(getCacheDir(context), "cached_$hash.$ext")
    }

    fun isCached(context: Context, url: String): Boolean {
        val file = getCacheFile(context, url)
        return file.exists() && file.length() > 0
    }

    fun saveBytes(context: Context, url: String, bytes: ByteArray) {
        val file = getCacheFile(context, url)
        val tmp = File(file.parentFile, "${file.name}.${System.currentTimeMillis()}.tmp")
        try {
            tmp.writeBytes(bytes)
            tmp.renameTo(file)
        } catch (t: Throwable) {
            Logger.w("ImageDiskCache", "Failed to cache $url to disk", t)
            tmp.delete()
        }
    }
}

/**
 * In-memory cache for decoded images with maximum cache size of 1/8th of available runtime memory.
 * Size is tracked in bytes (width * height * 4).
 */
private val maxCacheMemory = (Runtime.getRuntime().maxMemory() / 8).coerceAtLeast(4 * 1024 * 1024).toInt()

internal val imageBitmapCache = object : LruCache<String, ImageBitmap>(maxCacheMemory) {
    override fun sizeOf(key: String, value: ImageBitmap): Int {
        return value.width * value.height * 4
    }
}

// Map of in-flight download jobs to avoid duplicate simultaneous downloads for the same URL
private val inFlightLoads = ConcurrentHashMap<String, Deferred<ImageBitmap?>>()
private val inFlightMutex = Mutex()

/**
 * Decode bitmap with downsampling so photos (e.g. 12-48MP from phone camera)
 * don't allocate tens of megabytes of RAM and blow out the memory cache.
 */
internal fun decodeSampledBitmap(
    bytes: ByteArray,
    maxTargetWidth: Int = 1080,
    maxTargetHeight: Int = 1920
): Bitmap? {
    return try {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

        options.inSampleSize = calculateInSampleSize(options, maxTargetWidth, maxTargetHeight)
        options.inJustDecodeBounds = false
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    } catch (t: Throwable) {
        Logger.w("LoadedImage", "decodeSampledBitmap(ByteArray) failed", t)
        null
    }
}

internal fun decodeSampledBitmap(
    filePath: String,
    maxTargetWidth: Int = 1080,
    maxTargetHeight: Int = 1920
): Bitmap? {
    return try {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(filePath, options)

        options.inSampleSize = calculateInSampleSize(options, maxTargetWidth, maxTargetHeight)
        options.inJustDecodeBounds = false
        BitmapFactory.decodeFile(filePath, options)
    } catch (t: Throwable) {
        Logger.w("LoadedImage", "decodeSampledBitmap(File) failed for $filePath", t)
        null
    }
}

internal fun decodeSampledStream(
    openStream: () -> InputStream?,
    maxTargetWidth: Int = 1080,
    maxTargetHeight: Int = 1920
): Bitmap? {
    return try {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        openStream()?.use { BitmapFactory.decodeStream(it, null, options) }

        options.inSampleSize = calculateInSampleSize(options, maxTargetWidth, maxTargetHeight)
        options.inJustDecodeBounds = false
        openStream()?.use { BitmapFactory.decodeStream(it, null, options) }
    } catch (t: Throwable) {
        Logger.w("LoadedImage", "decodeSampledStream failed", t)
        null
    }
}

private fun calculateInSampleSize(
    options: BitmapFactory.Options,
    reqWidth: Int,
    reqHeight: Int
): Int {
    val height = options.outHeight
    val width = options.outWidth
    var inSampleSize = 1

    if (height > reqHeight || width > reqWidth) {
        val halfHeight = height / 2
        val halfWidth = width / 2
        while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize.coerceAtLeast(1)
}

/**
 * UI-thread-free image loader used by [com.example.notes.ui.edit.NoteEditScreen]
 * and [com.example.notes.ui.notes.NotesListScreen].
 *
 * Checks in-memory cache first, then disk cache, and only falls back to network
 * if the image has never been downloaded before.
 */
@Composable
fun rememberLoadedImage(uriString: String): ImageBitmap? {
    val context = LocalContext.current
    var bitmap by remember(uriString) { mutableStateOf(imageBitmapCache.get(uriString)) }
    LaunchedEffect(uriString) {
        if (bitmap == null) {
            bitmap = withContext(Dispatchers.IO) {
                loadImageBitmapFromUri(context, uriString)
            }
        }
    }
    return bitmap
}

/**
 * Decode the bytes that [uriString] points at into a Compose [ImageBitmap].
 * Thread-safe, cached on disk and memory, coalesces in-flight requests.
 */
internal suspend fun loadImageBitmapFromUri(
    context: Context,
    uriString: String
): ImageBitmap? = withContext(Dispatchers.IO) {
    if (uriString.isBlank()) return@withContext null

    imageBitmapCache.get(uriString)?.let { return@withContext it }

    val uri = uriString.toUri()
    val scheme = uri.scheme

    // Deduplicate in-flight network requests
    if (scheme == "http" || scheme == "https") {
        val existingJob = inFlightLoads[uriString]
        if (existingJob != null) {
            return@withContext existingJob.await()
        }
    }

    coroutineScope {
        val deferred = async(Dispatchers.IO) {
            loadDirect(context, uriString)
        }
        if (scheme == "http" || scheme == "https") {
            inFlightLoads[uriString] = deferred
        }
        try {
            val result = deferred.await()
            if (result != null) {
                imageBitmapCache.put(uriString, result)
            }
            result
        } finally {
            if (scheme == "http" || scheme == "https") {
                inFlightLoads.remove(uriString)
            }
        }
    }
}

private fun loadDirect(context: Context, uriString: String): ImageBitmap? {
    return try {
        val uri = uriString.toUri()
        when (uri.scheme) {
            "http", "https" -> {
                // 1. Check persistent disk cache first
                val cacheFile = ImageDiskCache.getCacheFile(context, uriString)
                if (cacheFile.exists() && cacheFile.length() > 0) {
                    val sampled = decodeSampledBitmap(cacheFile.absolutePath)
                    if (sampled != null) {
                        return sampled.asImageBitmap()
                    }
                }

                // 2. Not cached on disk: fetch via network
                val hasToken = !ApiClient.authToken.isNullOrBlank()
                val request = okhttp3.Request.Builder().url(uriString).build()
                ApiClient.imageClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Logger.w(
                            "LoadedImage",
                            "loadImageBitmapFromUri: HTTP ${resp.code} for $uriString (auth=${if (hasToken) "yes" else "NO"})"
                        )
                        null
                    } else {
                        val bytes = resp.body?.bytes()
                        if (bytes != null && bytes.isNotEmpty()) {
                            // Save to disk cache for all future offline & online opens
                            ImageDiskCache.saveBytes(context, uriString, bytes)
                            decodeSampledBitmap(bytes)?.asImageBitmap()
                        } else null
                    }
                }
            }
            "file" -> {
                val path = uri.path
                if (path != null && File(path).exists()) {
                    decodeSampledBitmap(path)?.asImageBitmap()
                } else {
                    decodeSampledStream({ context.contentResolver.openInputStream(uri) })?.asImageBitmap()
                }
            }
            else -> {
                decodeSampledStream({ context.contentResolver.openInputStream(uri) })?.asImageBitmap()
            }
        }
    } catch (e: Throwable) {
        Logger.w("LoadedImage", "loadImageBitmapFromUri failed for $uriString: ${e.message ?: e.javaClass.simpleName}", e)
        null
    }
}
