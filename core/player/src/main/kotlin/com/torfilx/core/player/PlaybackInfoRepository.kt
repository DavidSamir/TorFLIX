package com.torfilx.core.player

import com.torfilx.core.data.catalog.Catalog
import com.torfilx.core.model.PlaybackInfo
import com.torfilx.core.player.capability.DeviceCapabilitiesProvider
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the player gets its sources.
 *
 * With no media server, every source is a magnet from the catalogue in use. Failed sources are
 * remembered for the session so a quality that will not play is not retried on every attempt.
 */
@Singleton
class PlaybackInfoRepository @Inject constructor(
    private val catalog: Catalog,
    private val capabilitiesProvider: DeviceCapabilitiesProvider,
) {

    private val failedSources = ConcurrentHashMap<String, MutableSet<String>>()

    suspend fun playbackInfo(itemId: String): PlaybackInfo = PlaybackInfo(
        itemId = itemId,
        sources = catalog.sourcesFor(itemId),
        durationMs = catalog.item(itemId)?.item?.runtimeMs,
    )

    fun failedSourceIds(itemId: String): Set<String> = failedSources[itemId]?.toSet() ?: emptySet()

    fun markSourceFailed(itemId: String, sourceId: String) {
        failedSources.getOrPut(itemId) { ConcurrentHashMap.newKeySet() }.add(sourceId)
    }

    fun clearFailures(itemId: String) {
        failedSources.remove(itemId)
    }

    fun capabilities() = capabilitiesProvider.capabilities()
}
