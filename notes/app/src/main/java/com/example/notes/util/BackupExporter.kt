package com.example.notes.util

import android.content.Context
import android.net.Uri
import com.example.notes.data.local.NoteEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.OutputStream
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
 * The actual file write is delegated to the caller via [OutputStream]
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

    private val json = Json {
        ignoreUnknownKeys = true
        // Don't pretty-print — files can be huge; let the viewer handle
        // formatting.
        prettyPrint = false
        encodeDefaults = true
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
     * Write to an [OutputStream] opened by the caller (typically from a
     * `ContentResolver.openOutputStream` against the URI returned by the
     * Storage Access Framework).
     *
     * Returns the number of bytes written; throws the underlying I/O
     * exception on failure (the [SettingsScreen] wraps this in a
     * try/catch and surfaces a snackbar).
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
}
