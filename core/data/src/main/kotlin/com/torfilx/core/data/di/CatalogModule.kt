package com.torfilx.core.data.di

import android.content.Context
import com.torfilx.core.catalogue.crypto.Ed25519Verifier
import com.torfilx.core.catalogue.release.CatalogueReleaseVerifier
import com.torfilx.core.data.BuildConfig
import com.torfilx.core.data.catalog.AndroidCatalogAssetSource
import com.torfilx.core.data.catalog.AppVersionProvider
import com.torfilx.core.data.catalog.Catalog
import com.torfilx.core.data.catalog.CatalogAssetSource
import com.torfilx.core.data.catalog.CataloguePublisherKeys
import com.torfilx.core.data.catalog.CatalogueUpdatePrefs
import com.torfilx.core.data.catalog.FetchedCatalogStore
import com.torfilx.core.data.catalog.LayeredCatalog
import com.torfilx.core.data.catalog.PackageAppVersionProvider
import com.torfilx.core.data.settings.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CatalogBindingsModule {

    @Binds
    @Singleton
    abstract fun bindsCatalog(impl: LayeredCatalog): Catalog

    @Binds
    @Singleton
    abstract fun bindsCatalogAssetSource(impl: AndroidCatalogAssetSource): CatalogAssetSource

    @Binds
    @Singleton
    abstract fun bindsAppVersionProvider(impl: PackageAppVersionProvider): AppVersionProvider

    @Binds
    @Singleton
    abstract fun bindsCatalogueUpdatePrefs(impl: SettingsRepository): CatalogueUpdatePrefs
}

@Module
@InstallIn(SingletonComponent::class)
object CatalogModule {

    /** Downloaded releases live apart from torrent data, which the engine empties on every start. */
    @Provides
    @Singleton
    fun providesFetchedCatalogStore(@ApplicationContext context: Context): FetchedCatalogStore =
        FetchedCatalogStore { File(context.filesDir, "catalogue") }

    /** The keys come from the build: `-Ptorfilx.cataloguePublisherKeys`, or the production key. */
    @Provides
    @Singleton
    fun providesCataloguePublisherKeys(): CataloguePublisherKeys =
        CataloguePublisherKeys.parse(BuildConfig.CATALOGUE_PUBLISHER_KEYS)

    @Provides
    @Singleton
    fun providesCatalogueReleaseVerifier(keys: CataloguePublisherKeys, json: Json): CatalogueReleaseVerifier =
        CatalogueReleaseVerifier(Ed25519Verifier(keys.publicKeys), json)
}
