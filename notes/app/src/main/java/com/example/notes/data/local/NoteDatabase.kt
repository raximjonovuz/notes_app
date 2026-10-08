package com.example.notes.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [NoteEntity::class], version = 9, exportSchema = false)
abstract class NoteDatabase : RoomDatabase() {

    abstract fun noteDao(): NoteDao

    companion object {
        @Volatile
        private var INSTANCE: NoteDatabase? = null

        /**
         * V5.0.2 — bridges the gap between every pre-V3.2 schema and the V7
         * schema. Without this Room would throw `IllegalStateException:
         * A migration from N to 9 was required but not found` and silently
         * wipe the user's local notes on first launch after the update.
         *
         * Each step assumes the previous version had the prior columns
         * (matching the commit history of [NoteEntity]). The migrations
         * only ADD new columns with safe defaults so the existing data is
         * preserved verbatim.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // V2 added background color + font weight.
                db.execSQL("ALTER TABLE notes ADD COLUMN backgroundColor INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notes ADD COLUMN fontWeight INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Reserved for V3 (no schema change in V3 itself).
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // V4 added per-block text size index.
                db.execSQL("ALTER TABLE notes ADD COLUMN textSize INTEGER NOT NULL DEFAULT 1")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // V5 added the imageUris column for inline images.
                db.execSQL("ALTER TABLE notes ADD COLUMN imageUris TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Reserved for V3.0 → V3.1 shape change (no-op, default textSize).
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // V3.1 added the serverId UUID (nullable).
                db.execSQL("ALTER TABLE notes ADD COLUMN serverId TEXT")
            }
        }

        /**
         * V3.2 — adds the `userId` column to `notes` so each signed-in account
         * only sees its own notes. Existing rows default to 0 (anonymous / pre-
         * login local), and the destructive fallback below catches the edge
         * case where the migration is not run for some reason.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE notes ADD COLUMN userId INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * V4.5 — adds the `isPinned` column. Defaults to 0 (false) for all
         * existing rows so the upgrade is invisible to the user.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE notes ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0"
                )
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
                        MIGRATION_8_9
                    )
                    // V4.4: never silently wipe user notes on a schema bump.
                    // Every new schema version must ship with an explicit
                    // Migration (or MigrationTestHelper) so the build fails
                    // loudly during development if a migration is missing.
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}