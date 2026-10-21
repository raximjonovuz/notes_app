package com.example.notes.ui.util

import android.content.Context
import com.example.notes.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Background prefetcher for notes images.
 * Scans image URIs across all notes and downloads any missing images
 * to [ImageDiskCache] in background so when the user views the notes list
 * or taps into a note, the images load instantly with zero delay or placeholders.
 */
object ImagePrefetcher {
    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun prefetch(context: Context, uris: Collection<String>) {
        val targets = uris
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
            .filterNot { ImageDiskCache.isCached(context, it) }

        if (targets.isEmpty()) return

        prefetchScope.launch {
            for (url in targets) {
                try {
                    loadImageBitmapFromUri(context, url)
                } catch (t: Throwable) {
                    Logger.w("ImagePrefetcher", "Prefetch failed for $url", t)
                }
            }
        }
    }
}
