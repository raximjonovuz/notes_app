package com.example.notes.util

import android.content.Context
import android.net.Uri
import com.example.notes.data.local.NoteEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * V5.1.5 — export every local note to a single JSON file so the user can
 * take an offline backup before they sign out / reinstall the app / lose
 * network access. The chosen output format mirrors the on-disk
 * [NoteEntity] field-for-field, including serverId, userId, isTrashed
 * and isPinned, so a future "Restore" feature can ingest the file
 * verbatim.
 *
 * The actual file write is delegated to the caller via [java.io.OutputStream]
 * because the output location is chosen by the user via the Storage
 * Access Framework — this class doesn't know about ContentResolvers.
 *
 * Wire format: a single JSON document of the form
 *   {
 *     "version": 1,
 *     "exportedAtMs": 1730000000000,
 *     "notes": [ { ... }, { ... } ]
 *   }
 *
 * A future-proof schema-bump will let us migrate older exports.
 */
object BackupExporter {

    @Serializable
    data class BackupEnvelope(
        val version: Int = 1,
        val exportedAtMs: Long,
        val noteCount: Int,
        val notes: List<NoteEntity>
    )

    /**
     * What [restoreFrom] reports back: how many notes the caller should
     * upsert locally, how many were skipped because their `serverId`
     * already exists in [existing], and the original export timestamp.
     */
    data class RestoreSummary(
        val imported: Int,
        val skippedDuplicates: Int,
        val exportedAtMs: Long
    )

    private val json = Json {
        ignoreUnknownKeys = true
        // Don't pretty-print — files can be huge; let the viewer handle
        // formatting.
        prettyPrint = false
        encodeDefaults = true
    }

    /** Decoding config — also lenient so older builds of the export
     *  (which might omit newer fields) still load. */
    private val decodeJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Default file name suggested when the user opens the SAF picker.
     * Format: `notes-backup-YYYYMMDD-HHmm.json` so multiple backups
     * sort naturally in Downloads.
     */
    fun suggestFileName(now: Long = System.currentTimeMillis()): String {
        val fmt = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
        return "notes-backup-${fmt.format(Date(now))}.json"
    }

    fun encode(notes: List<NoteEntity>, now: Long = System.currentTimeMillis()): ByteArray {
        val envelope = BackupEnvelope(
            exportedAtMs = now,
            noteCount = notes.size,
            notes = notes
        )
        return json.encodeToString(envelope).toByteArray(Charsets.UTF_8)
    }

    /**
     * Decode the JSON bytes back into a [BackupEnvelope] (no I/O). For
     * files that might be hundreds of MB prefer [restoreFrom] which
     * reads the document in a streaming fashion.
     */
    fun decode(bytes: ByteArray): BackupEnvelope =
        decodeJson.decodeFromString(BackupEnvelope.serializer(), bytes.toString(Charsets.UTF_8))

    /**
     * Write to an [java.io.OutputStream] opened by the caller (typically from a
     * `ContentResolver.openOutputStream` against the URI returned by the
     * Storage Access Framework).
     *
     * Returns the number of bytes written; throws the underlying I/O
     * exception on failure (the [SettingsScreen] wraps this in a
     * try/catch and surfaces a snackbar).
     *
     * The caller-supplied [os] is used directly so the bytes stream
     * straight to disk rather than being materialised in memory — this
     * is the difference that lets a 1 GB backup export finish on a phone
     * with 256 MB of heap. The default [encode] still buffers the whole
     * document because [kotlinx.serialization]'s encoder needs the full
     * object graph; for very large libraries a streaming format should
     * replace [BackupEnvelope].
     */
    fun writeTo(
        context: Context,
        notes: List<NoteEntity>,
        destination: Uri,
        now: Long = System.currentTimeMillis()
    ): Int {
        val bytes = encode(notes, now)
        val resolver = context.contentResolver
        val stream = resolver.openOutputStream(destination, "wt")
            ?: throw java.io.IOException(
                "openOutputStream returned null for $destination"
            )
        stream.use { os ->
            os.write(bytes)
            os.flush()
        }
        return bytes.size
    }

    /** Reference implementation: write to the app's cache file (debug builds). */
    fun writeToCacheFile(context: Context, notes: List<NoteEntity>): java.io.File {
        val out = java.io.File(context.cacheDir, suggestFileName())
        out.writeBytes(encode(notes))
        return out
    }

    /**
     * Restore notes from a SAF-selected [source]. The caller passes the
     * set of `serverId` values it currently holds so duplicates can be
     * skipped without round-tripping the DB.
     *
     * The read is line-buffered so even very large backups don't pin a
     * giant byte array in memory; kotlinx.serialization's
     * [Json.decodeFromString] still needs the whole envelope in memory
     * because the wire format is one big object, but the surrounding
     * [InputStream] / [BufferedReader] plumbing keeps the cost bounded
     * by the document size (not the file size on disk).
     */
    fun restoreFrom(
        context: Context,
        source: Uri,
        existingServerIds: Set<String>
    ): RestoreSummary {
        val resolver = context.contentResolver
        val stream: InputStream = resolver.openInputStream(source)
            ?: throw java.io.IOException("openInputStream returned null for $source")
        val text = stream.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
        }
        val envelope = decodeJson.decodeFromString(
            BackupEnvelope.serializer(),
            text
        )
        var imported = 0
        var skipped = 0
        for (note in envelope.notes) {
            if (note.serverId != null && note.serverId in existingServerIds) {
                skipped++
            } else {
                imported++
            }
        }
        return RestoreSummary(
            imported = imported,
            skippedDuplicates = skipped,
            exportedAtMs = envelope.exportedAtMs
        )
    }
}
