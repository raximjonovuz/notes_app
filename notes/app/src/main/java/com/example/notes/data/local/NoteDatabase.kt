package com.example.notes.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [NoteEntity::class], version = 11, exportSchema = true)
abstract class NoteDatabase : RoomDatabase() {

    abstract fun noteDao(): NoteDao

    companion object {
        @Volatile
        private var INSTANCE: NoteDatabase? = null

        // V2 — first migration chain, kept verbatim.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN backgroundColor INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notes ADD COLUMN fontWeight INTEGER NOT NULL DEFAULT 0")
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {}
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN textSize INTEGER NOT NULL DEFAULT 1")
            }
        }
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN imageUris TEXT NOT NULL DEFAULT ''")
            }
        }
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {}
        }
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN serverId TEXT")
            }
        }
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN userId INTEGER NOT NULL DEFAULT 0")
            }
        }
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * V5.3 (BUG 1, 2, 3, 4, 5 fixes) — adds the sync-state columns
         * that the rewritten push/pull/merge logic depends on. Safe
         * defaults so existing data carries over verbatim.
         *
         * - dirtyRevision=1:        every existing row counts as "needs to
         *                          be uploaded once". Subsequent pushes
         *                          won't re-upload rows we already
         *                          confirmed via UPSERT.
         * - lastSyncedRevision=0:  matches "never pushed". The first push
         *                          will clear it because the server will
         *                          always treat our payload as fresh.
         * - serverChangeId=0:      unknown until the first pull.
         * - deletedAt=NULL:        default alive.
         * - isConflict=false,
         *   baseTitle/baseContent=NULL: no prior 3-way merge baseline.
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN deletedAt INTEGER")
                db.execSQL("ALTER TABLE notes ADD COLUMN dirtyRevision INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE notes ADD COLUMN lastSyncedRevision INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notes ADD COLUMN serverChangeId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notes ADD COLUMN serverUpdatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notes ADD COLUMN isConflict INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notes ADD COLUMN conflictOriginalId TEXT")
                db.execSQL("ALTER TABLE notes ADD COLUMN baseTitle TEXT")
                db.execSQL("ALTER TABLE notes ADD COLUMN baseContent TEXT")

                db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_serverId ON notes(serverId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_userId ON notes(userId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_deletedAt ON notes(deletedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_userId_dirtyRevision ON notes(userId, dirtyRevision)")
            }
        }

        /**
         * B3 — `serverId` becomes a local UUID stamped at insert time,
         * with a UNIQUE index to prevent the duplicate-row class of
         * bugs the previous nullable `serverId` allowed.
         *
         * The migration has to handle three pre-existing edge cases
         * before the unique index can be created:
         *
         *   1. `serverId IS NULL` rows (notes that have never been
         *      pushed). Generate a fresh UUID for each.
         *
         *   2. Duplicate `serverId` values (the legacy push path could
         *      accidentally assign the same UUID to two local rows when
         *      a previous response was lost and a retry used a freshly
         *      generated id). Keep the oldest row (smallest primary
         *      key) untouched, give every other row a fresh UUID.
         *
         *   3. The old non-unique `index_notes_serverId` index from
         *      MIGRATION_9_10 must be dropped so the new unique index
         *      with the same column can be created.
         *
         * No `fallbackToDestructiveMigration` is used: existing data
         * must survive this migration.
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Pass 1 — dedupe existing serverId values. Walk the
                // table in primary-key order so the first row for each
                // duplicate serverId is the "keeper" and the rest get
                // fresh UUIDs. NULLs are handled in pass 2.
                db.query("SELECT id, serverId FROM notes ORDER BY id ASC").use { cursor ->
                    val seen = HashSet<String>()
                    val dupUpdates = ArrayList<Pair<Long, String>>()
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        if (cursor.isNull(1)) continue
                        val serverId = cursor.getString(1)
                        if (serverId.isNullOrEmpty()) continue
                        if (!seen.add(serverId)) {
                            dupUpdates.add(id to java.util.UUID.randomUUID().toString())
                        }
                    }
                    for ((id, newServerId) in dupUpdates) {
                        db.execSQL(
                            "UPDATE notes SET serverId = ? WHERE id = ?",
                            arrayOf<Any>(newServerId, id)
                        )
                    }
                }

                // Pass 2 — fill NULL serverIds. After pass 1, every
                // remaining non-NULL value is unique.
                db.query("SELECT id FROM notes WHERE serverId IS NULL OR serverId = ''").use { cursor ->
                    val nullIds = ArrayList<Long>()
                    while (cursor.moveToNext()) {
                        nullIds.add(cursor.getLong(0))
                    }
                    for (id in nullIds) {
                        db.execSQL(
                            "UPDATE notes SET serverId = ? WHERE id = ?",
                            arrayOf<Any>(java.util.UUID.randomUUID().toString(), id)
                        )
                    }
                }

                // Pass 3 — drop the non-unique index from MIGRATION_9_10
                // and create the new unique one. Index name matches
                // Room's auto-generated name for `@Index(value=["serverId"], unique=true)`.
                db.execSQL("DROP INDEX IF EXISTS index_notes_serverId")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_notes_serverId_unique ON notes(serverId)")
            }
        }

        fun getDatabase(context: Context): NoteDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NoteDatabase::class.java,
                    "notes_database"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11
                    )
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
