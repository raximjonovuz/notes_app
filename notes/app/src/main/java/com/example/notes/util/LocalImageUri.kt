package com.example.notes.util

import android.content.Context
import com.example.notes.ui.edit.ContentBlock
import com.example.notes.ui.edit.parseBlocksFromContent
import com.example.notes.ui.edit.serializeBlocksToContent

/**
 * F-05 (part 2) — single source of truth for "is this image URI a
 * local, device-only file?" and for turning local content into the
 * copy that is safe to put on the wire.
 *
 * The problem this solves
 * ----------------------
 * The editor captures a picked image into the app-private sandbox
 * (`filesDir/images/img_<UUID>.<ext>`) and stores the local path
 * (`file://<filesDir>/images/img_<UUID>.<ext>`) inside the note's
 * content. The R2 upload that replaces the local path with the
 * public URL happens asynchronously in the background, and it can
 * fail (backend 5xx, R2 quota, no connectivity).
 *
 * When the upload keeps failing, the push pass eventually gives up
 * on the image (see [ImageRetryStore]) so the note's TEXT can still
 * reach the server. But the note's content still contains the local
 * `file://` URI at that point, and pushing it verbatim would:
 *   - leak this device's absolute filesystem path to the server and
 *     to every other account's device,
 *   - render as a broken image everywhere except this phone,
 *   - and give the user no idea that the image never uploaded.
 *
 * [sanitizeForWire] produces the copy that is actually sent: the
 * local `file://` image URIs are replaced by a short, obviously-
 * synthetic marker. The LOCAL row keeps its `file://` URI
 * untouched, so the editor can still render the image and can
 * still re-upload it later.
 *
 * Marker format
 * -------------
 * `pending-image:<basename>` — e.g. `pending-image:img_1f2e3d.png`.
 *
 * Why the marker and not removing the block entirely:
 *   - Removing the block would make the server's copy LOSE the
 *     fact that an image belongs there. The next push (after the
 *     upload succeeds) would restore it, but if the user never
 *     retries, the image silently disappears from the note on every
 *     other device with no trace of what was lost.
 *   - The marker keeps the block's position and count, so the
 *     server copy still renders "there was an image here" as a
 *     placeholder, and the diff between the local and server
 *     versions is one token instead of a whole missing block.
 *   - The marker contains NO filesystem path, so nothing about
 *     this device leaks.
 *
 * The marker is deliberately NOT a URI with a scheme: `Uri.parse`
 * yields `scheme == null` for it, so the image loader
 * ([com.example.notes.ui.util.rememberLoadedImage]) takes the
 * `contentResolver.openInputStream` branch, fails, and returns
 * `null` — the UI renders the standard image placeholder. No
 * crash, no network request to a bogus host.
 */
object LocalImageUri {

    /**
     * Prefix of the synthetic marker written into the server copy.
     */
    const val MARKER_PREFIX = "pending-image:"

    /**
     * The prefix the editor uses for NEW images (V5.3.4+ layout,
     * `filesDir/images/`).
     */
    fun newPrefix(context: Context): String =
        "file://" + context.filesDir.absolutePath + "/images/"

    /**
     * The prefix the editor used BEFORE V5.3.4 (`filesDir/` root).
     * Notes that haven't been through [LocalImageMigration] still
     * carry these.
     */
    fun legacyPrefix(context: Context): String =
        "file://" + context.filesDir.absolutePath + "/"

    /**
     * True when [uri] points at a file that only exists on this
     * device (i.e. it must not be sent to the server verbatim).
     */
    fun isLocalImageUri(uri: String, context: Context): Boolean =
        uri.startsWith(newPrefix(context)) || uri.startsWith(legacyPrefix(context))

    /**
     * True when [uri] is already one of our synthetic markers.
     */
    fun isMarker(uri: String): Boolean = uri.startsWith(MARKER_PREFIX)

    /**
     * Build the synthetic marker for a local [uri]. Only the file's
     * basename is kept — it is a random UUID, so it identifies the
     * image without revealing anything about the device.
     */
    fun toMarker(uri: String): String =
        MARKER_PREFIX + uri.substringAfterLast('/').substringAfterLast('\\')

    /**
     * Produce the copy of [content] that is safe to send to the
     * server: every local `file://` image URI is replaced by its
     * [toMarker] equivalent. Everything else — text, styles, the
     * public R2 URLs, the synthetic markers themselves — is left
     * exactly as it was.
     *
     * The content is parsed with the shared
     * [parseBlocksFromContent] / [serializeBlocksToContent] pair so
     * the JSON v2 envelope (and the legacy V2.6 header format) stay
     * structurally valid; we never do a raw string replace on the
     * serialized bytes.
     *
     * Idempotent: running it on already-sanitized content returns
     * the same string.
     */
    fun sanitizeForWire(content: String, context: Context): String {
        if (content.isEmpty()) return content
        val blocks = parseBlocksFromContent(content)
        var mutated = false
        val sanitized = blocks.map { block ->
            if (block is ContentBlock.ImageBlock &&
                !block.uri.isEmpty() &&
                isLocalImageUri(block.uri, context)
            ) {
                mutated = true
                block.copy(uri = toMarker(block.uri))
            } else block
        }
        if (!mutated) return content
        return serializeBlocksToContent(sanitized)
    }
}
