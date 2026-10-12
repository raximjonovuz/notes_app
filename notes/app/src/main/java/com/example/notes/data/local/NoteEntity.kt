package com.example.notes.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * V5.3 — extends V4.5 with sync-state columns needed to fix the 8
 * previously-identified sync bugs:
 *
 *   - `deletedAt: Long?`        — BUG 2: soft-delete. NULL = note is live,
 *                                  non-null = tombstoned. Replaces hard
 *                                  DELETE on the server.
 *   - `dirtyRevision: Int`      — BUG 1: bumps whenever the row mutates
 *                                  locally (text edit, favorite, pin,
 *                                  trash, color). Push drains rows where
 *                                  dirtyRevision > lastSyncedRevision,
 *                                  and resets it on successful upload.
 *   - `lastSyncedRevision: Int` — the dirtyRevision the server last
 *                                  acknowledged. Persistent mirror of the
 *                                  push-side state machine.
 *   - `serverChangeId: Long`    — BUG 4: monotonic change_log id the
 *                                  server assigned to the most recent
 *                                  version of this row. Used as the pull
 *                                  cursor instead of a millisecond.
 *   - `serverUpdatedAt: Long`   — BUG 5: server's authoritative timestamp
 *                                  for the synced content (== client's
 *                                  client_updated_at).
 *   - `isConflict: Boolean`,
 *     `conflictOriginalId: String?`
 *                                — BUG 3: set to true by 3-way merge
 *                                  when local and server diverge and we
 *                                  can't auto-merge. conflictOriginalId
 *                                  points at the row whose version kept
 *                                  on the canonical side.
 *   - `baseTitle: String?`,
 *     `baseContent: String?`    — BUG 3: the last-known common content
 *                                  for 3-way merge. Null on first sync.
 *                                  Updated whenever a push or pull
 *                                  completes successfully.
 */
@Entity(
    tableName = "notes",
    indices = [
        Index("serverId"),
        Index("userId"),
        Index("deletedAt"),
        Index(value = ["userId", "dirtyRevision"])
    ]
)
@Serializable
data class NoteEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String = "",
    val content: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false,
    val isTrashed: Boolean = false,
    val backgroundColor: Int = 0,
    val fontWeight: Int = 0,
    val textSize: Int = 0,
    val imageUris: String = "",
    val serverId: String? = null,
    val userId: Int = 0,
    val isPinned: Boolean = false,

    // V5.3 sync state
    val deletedAt: Long? = null,
    val dirtyRevision: Int = 1,
    val lastSyncedRevision: Int = 0,
    val serverChangeId: Long = 0,
    val serverUpdatedAt: Long = 0,
    val isConflict: Boolean = false,
    val conflictOriginalId: String? = null,
    val baseTitle: String? = null,
    val baseContent: String? = null
) {
    /** True for non-deleted rows; used in every read query. */
    val isLive: Boolean get() = deletedAt == null
}
