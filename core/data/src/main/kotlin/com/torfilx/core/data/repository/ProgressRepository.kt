package com.torfilx.core.data.repository

import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.core.common.time.TimeProvider
import com.torfilx.core.data.catalog.Catalog
import com.torfilx.core.data.database.ProgressDao
import com.torfilx.core.data.database.ProgressEntity
import com.torfilx.core.model.MediaCard
import com.torfilx.core.model.PlaybackProgress
import com.torfilx.core.model.ResumeRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Progress"

/**
 * Playback progress: the one piece of state the viewer really notices losing.
 *
 * Everything is local: written to Room the moment it changes, so it survives the app being killed,
 * the device rebooting and playback failing halfway.
 */
@Singleton
class ProgressRepository @Inject constructor(
    private val progressDao: ProgressDao,
    private val catalog: Catalog,
    private val timeProvider: TimeProvider,
) {

    fun observe(itemId: String): Flow<PlaybackProgress?> =
        progressDao.observe(itemId).map { it?.toDomain() }

    /** Every known progress row, keyed by item id; used to decorate rows and grids. */
    fun observeAllProgress(): Flow<Map<String, PlaybackProgress>> =
        progressDao.observeEverything().map { list -> list.associate { it.itemId to it.toDomain() } }

    suspend fun currentProgressMap(): Map<String, PlaybackProgress> =
        progressDao.all().associate { it.itemId to it.toDomain() }

    suspend fun get(itemId: String): PlaybackProgress? = progressDao.get(itemId)?.toDomain()

    /**
     * Records a playback position. Called every ~10 s while playing and on every pause/seek/stop, so
     * it must be cheap and must never throw into the player.
     */
    suspend fun save(
        itemId: String,
        positionMs: Long,
        durationMs: Long,
        watchedOverride: Boolean? = null,
    ) {
        val safePosition = positionMs.coerceAtLeast(0)
        val safeDuration = durationMs.coerceAtLeast(0)
        val fraction = if (safeDuration <= 0) 0f else safePosition.toFloat() / safeDuration
        val watched = watchedOverride ?: (fraction >= ResumeRules.WATCHED_THRESHOLD)

        runCatching {
            progressDao.upsert(
                ProgressEntity(
                    itemId = itemId,
                    positionMs = safePosition,
                    durationMs = safeDuration,
                    watched = watched,
                    updatedAtMs = timeProvider.writeTimestampMs(),
                ),
            )
        }.onFailure { TorfilxLog.e(TAG, "Failed to persist progress for $itemId", it) }
    }

    /** Marks a film watched (Menu → Mark watched) without playing it. */
    suspend fun markWatched(itemId: String, durationMs: Long?, watched: Boolean) {
        val existing = progressDao.get(itemId)
        val duration = durationMs ?: existing?.durationMs ?: 0L
        save(
            itemId = itemId,
            positionMs = if (watched) duration else 0L,
            durationMs = duration,
            watchedOverride = watched,
        )
    }

    /** Removes a film from Continue Watching (Menu → Remove). */
    suspend fun remove(itemId: String) {
        progressDao.delete(itemId)
    }

    /**
     * Continue Watching, newest first.
     *
     * Entries whose film is not in the catalogue in use are skipped rather than shown as a blank card.
     * The catalogue can change while the app runs, so the row is re-evaluated whenever it does; the
     * progress itself is kept, and reappears if a later catalogue brings the film back.
     */
    fun observeContinueWatching(limit: Int = CONTINUE_WATCHING_LIMIT): Flow<List<MediaCard>> =
        combine(progressDao.observeEverything(), catalog.info) { rows, _ ->
            rows.map { it.toDomain() }
                .filter { ResumeRules.belongsInContinueWatching(it) }
                .sortedByDescending { it.updatedAtMs }
                .mapNotNull { progress ->
                    catalog.item(progress.itemId)?.item?.let { item ->
                        MediaCard(item = item, progress = progress)
                    }
                }
                .take(limit)
        }

    private fun ProgressEntity.toDomain(): PlaybackProgress = PlaybackProgress(
        itemId = itemId,
        positionMs = positionMs,
        durationMs = durationMs,
        watched = watched,
        updatedAtMs = updatedAtMs,
    )

    companion object {
        const val CONTINUE_WATCHING_LIMIT = 20
    }
}
