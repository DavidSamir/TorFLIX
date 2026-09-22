package com.torfilx.core.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 1 → 2: the contribution record.
 *
 * Purely additive — two new tables and an index, nothing existing is touched — so watch progress,
 * My List and search history pass through untouched. That property is the whole reason this is safe
 * to ship: destructive fallback is off, and a migration that got an existing table wrong would be an
 * unrecoverable startup crash on every installed device rather than a bug someone can report.
 *
 * The column definitions must match what the Room compiler exports for version 2 exactly, including
 * NOT NULL and the index name, or `runMigrationsAndValidate` fails the build — which is the point.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `contribution` (
                `infoHash` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `uploadedBytes` INTEGER NOT NULL,
                `downloadedBytes` INTEGER NOT NULL,
                `sizeBytes` INTEGER NOT NULL,
                `firstSharedAtMs` INTEGER NOT NULL,
                `lastActiveAtMs` INTEGER NOT NULL,
                `stillOnDisk` INTEGER NOT NULL,
                PRIMARY KEY(`infoHash`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_contribution_lastActiveAtMs` " +
                "ON `contribution` (`lastActiveAtMs`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `contribution_day` (
                `epochDay` INTEGER NOT NULL,
                `uploadedBytes` INTEGER NOT NULL,
                `downloadedBytes` INTEGER NOT NULL,
                PRIMARY KEY(`epochDay`)
            )
            """.trimIndent(),
        )
    }
}

/**
 * 2 → 3: per-show state, for dismissing an "up next" card from Continue Watching.
 *
 * Additive again — one new table, nothing existing touched. `dismissedAfterEpisodeId` is nullable so
 * later per-show state can live in the same row without a dismissal.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `show_state` (
                `showId` TEXT NOT NULL,
                `dismissedAfterEpisodeId` TEXT,
                `updatedAtMs` INTEGER NOT NULL,
                PRIMARY KEY(`showId`)
            )
            """.trimIndent(),
        )
    }
}
