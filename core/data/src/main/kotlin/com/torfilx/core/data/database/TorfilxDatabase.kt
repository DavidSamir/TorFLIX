package com.torfilx.core.data.database

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Schemas are exported to `core/data/schemas` and committed, so every future migration is
 * reviewable and testable (plan.md §8.1). `fallbackToDestructiveMigration` is never enabled —
 * losing playback progress on upgrade is not acceptable.
 *
 * Because destructive fallback is off, **bumping [version] without a migration is a hard startup
 * crash on every already-installed device.** To change the schema safely:
 *  1. bump [version];
 *  2. write a `Migration(N, N+1)` and add it to the Room builder in `DataModule`;
 *  3. extend `TorfilxDatabaseMigrationTest` with a `runMigrationsAndValidate(...)` for the new step.
 * The exported JSON under `core/data/schemas` is generated automatically by the Room compiler and
 * must be committed alongside the change — it is the fixture the migration test replays.
 */
@Database(
    entities = [
        ProgressEntity::class,
        MyListEntity::class,
        SearchHistoryEntity::class,
        ContributionEntity::class,
        ContributionDayEntity::class,
        ShowStateEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class TorfilxDatabase : RoomDatabase() {
    abstract fun progressDao(): ProgressDao
    abstract fun myListDao(): MyListDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun contributionDao(): ContributionDao
    abstract fun showStateDao(): ShowStateDao

    companion object {
        const val NAME = "torfilx.db"
    }
}
