package com.torfilx.core.torrent.di

import com.torfilx.core.catalogue.transport.CatalogueTransport
import com.torfilx.core.torrent.LibTorrentEngine
import com.torfilx.core.torrent.TorrentEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TorrentModule {
    @Binds
    @Singleton
    abstract fun bindsTorrentEngine(impl: LibTorrentEngine): TorrentEngine

    /** Catalogue releases travel over the same session as the films. */
    @Binds
    @Singleton
    abstract fun bindsCatalogueTransport(impl: LibTorrentEngine): CatalogueTransport
}
