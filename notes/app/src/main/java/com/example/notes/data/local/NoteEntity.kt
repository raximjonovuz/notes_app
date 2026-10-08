package com.example.notes.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(tableName = "notes")
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
    // V2 styling
    val backgroundColor: Int = 0,  // 0 = default white, 1 = yellow, 2 = blue, 3 = pink
    val fontWeight: Int = 0,       // 0 = normal, 1 = bold (kept for backwards compatibility)
    // V4 additions
    val textSize: Int = 0,         // 0 = small (14sp), 1 = medium (16sp), 2 = large (18sp)
    // V5: inline image URIs stored as a "|||" separated string. Empty means
    // no images. Kept in sync with the embedded image tokens inside `content`
    // so a single note never loses an image when loading or saving.
    val imageUris: String = "",
    // V3.1: maps this note to a server-side UUID. NULL means the note has
    // never been synced to the backend (or was created locally and has not
    // yet been pushed). We use a separate column rather than reusing `id`
    // because the local PK is a local auto-increment Long while the server
    // PK is a UUID string.
    val serverId: String? = null,
    // V3.2: which signed-in user owns this note. 0 = "anonymous / pre-login
    // local". Filters the notes shown by NotesListViewModel so each account
    // sees only its own data. Set on insert (via current session) and on
    // sync (from server `user_id`).
    val userId: Int = 0,
    // V4.5: pinned notes float to the top of the list regardless of
    // updatedAt. The list query sorts by `isPinned DESC, updatedAt DESC`.
    val isPinned: Boolean = false
)