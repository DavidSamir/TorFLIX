package com.torfilx.core.data.settings

import com.torfilx.core.model.LibrarySort
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import javax.inject.Singleton

/** How the Movies and Shows grids look when they open. The viewer can still change both on screen. */
data class LibraryDefaults(
    val sort: LibrarySort = LibrarySort.DEFAULT,
    val hideWatched: Boolean = false,
)

/**
 * The Library screen's view of the settings.
 *
 * An interface so the Library can be tested without an Android context; [SettingsRepository] is the
 * only real implementation.
 */
interface LibraryPreferences {
    val libraryDefaults: Flow<LibraryDefaults>
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LibraryPreferencesModule {
    @Binds
    @Singleton
    abstract fun bindsLibraryPreferences(impl: SettingsRepository): LibraryPreferences
}
