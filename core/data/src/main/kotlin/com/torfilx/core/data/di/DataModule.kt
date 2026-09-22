package com.torfilx.core.data.di

import android.content.Context
import androidx.room.Room
import com.torfilx.core.data.database.MyListDao
import com.torfilx.core.data.database.TorfilxDatabase
import com.torfilx.core.data.database.ProgressDao
import com.torfilx.core.data.database.SearchHistoryDao
import com.torfilx.core.data.database.ContributionDao
import com.torfilx.core.data.database.MIGRATION_1_2
import com.torfilx.core.data.database.MIGRATION_2_3
import com.torfilx.core.data.database.ShowStateDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun providesDatabase(@ApplicationContext context: Context): TorfilxDatabase =
        Room.databaseBuilder(context, TorfilxDatabase::class.java, TorfilxDatabase.NAME)
            // No destructive fallback: losing watch progress on an upgrade is not acceptable, so a
            // missing migration must fail loudly in development instead of silently wiping data.
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()

    @Provides
    fun providesProgressDao(database: TorfilxDatabase): ProgressDao = database.progressDao()

    @Provides
    fun providesMyListDao(database: TorfilxDatabase): MyListDao = database.myListDao()

    @Provides
    fun providesSearchHistoryDao(database: TorfilxDatabase): SearchHistoryDao =
        database.searchHistoryDao()

    @Provides
    fun providesContributionDao(database: TorfilxDatabase): ContributionDao =
        database.contributionDao()

    @Provides
    fun providesShowStateDao(database: TorfilxDatabase): ShowStateDao = database.showStateDao()
}

/**
 * JSON parsing for the bundled catalogue.
 *
 * Lenient on purpose: a hand-edited `catalog.json` with an extra field, a missing optional or a
 * trailing comma-style quirk should still load the titles it can, rather than leaving the app empty.
 */
@Module
@InstallIn(SingletonComponent::class)
object SerializationModule {

    @Provides
    @Singleton
    fun providesJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        isLenient = true
    }
}
