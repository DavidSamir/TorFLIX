package com.torfilx.core.data.repository

import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.core.common.time.TimeProvider
import com.torfilx.core.data.catalog.Catalog
import com.torfilx.core.data.catalog.Playable
import com.torfilx.core.data.database.ProgressDao
import com.torfilx.core.data.database.ProgressEntity
import com.torfilx.core.data.database.ShowStateDao
import com.torfilx.core.data.database.ShowStateEntity
import com.torfilx.core.model.ContinueWatching
import com.torfilx.core.model.Episode
import com.torfilx.core.model.MediaCard
import com.torfilx.core.model.PlaybackProgress
import com.torfilx.core.model.ResumeRules
import com.torfilx.core.model.ShowPlayRules
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
    private val showStateDao: ShowStateDao,
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

    /**
     * Marks a film or an episode watched (Menu → Mark watched) without playing it.
     *
     * Given a show's id, marks every regular episode of the show instead — a show has no progress of
     * its own, and writing a row under its id would only be a row nothing reads. Specials are left
     * alone either way: they are extras, not part of "have I seen this show".
     */
    suspend fun markWatched(itemId: String, durationMs: Long?, watched: Boolean) {
        val show = catalog.item(itemId)?.takeIf { it.item.isShow }
        if (show != null) {
            markEpisodes(ShowPlayRules.viewingOrder(show.seasons), watched)
            return
        }
        val existing = progressDao.get(itemId)
        val duration = durationMs ?: existing?.durationMs ?: 0L
        save(
            itemId = itemId,
            positionMs = if (watched) duration else 0L,
            durationMs = duration,
            watchedOverride = watched,
        )
    }

    /** Marks one season of a show watched or unwatched, from the details screen's season menu. */
    suspend fun markSeasonWatched(showId: String, season: Int, watched: Boolean) {
        val episodes = catalog.seasons(showId).firstOrNull { it.number == season }?.episodes.orEmpty()
        markEpisodes(episodes, watched)
    }

    /**
     * Writes or clears many episodes in one transaction, so an interrupted write never leaves half a
     * season marked.
     *
     * Watched: each episode at its end, with its own runtime (or the one already stored). Unwatched:
     * the rows are removed, which is what "never watched" looks like — writing a zero position for
     * every episode of a long show would only be hundreds of rows saying nothing.
     */
    private suspend fun markEpisodes(episodes: List<Episode>, watched: Boolean) {
        if (episodes.isEmpty()) return
        runCatching {
            if (watched) {
                val stored = progressDao.all().associateBy { it.itemId }
                val now = timeProvider.writeTimestampMs()
                progressDao.upsertAll(
                    episodes.map { episode ->
                        val duration = episode.runtimeMs ?: stored[episode.id]?.durationMs ?: 0L
                        ProgressEntity(episode.id, positionMs = duration, durationMs = duration, watched = true, updatedAtMs = now)
                    },
                )
            } else {
                progressDao.deleteAll(episodes.map { it.id })
            }
        }.onFailure { TorfilxLog.e(TAG, "Failed to mark ${episodes.size} episodes watched=$watched", it) }
    }

    /**
     * Menu → Remove on a Continue Watching card, given the id the card plays.
     *
     * A film's card, or a show's card for an episode left part-way, is its progress row, and removing
     * it deletes the row — the resume point goes with it, as it always has.
     *
     * A show's card is also held up by the episode last finished, which puts the one after it "up
     * next". That is progress the viewer means to keep, so it is not deleted; instead the dismissal is
     * recorded against that finished episode. Otherwise removing a part-watched episode's card would
     * only swap it for an "up next" card, and removing an "up next" card (which has no row of its own)
     * would do nothing at all. The card comes back once the viewer finishes another episode.
     */
    suspend fun remove(itemId: String) {
        runCatching {
            val playable = catalog.playable(itemId)
            progressDao.delete(itemId)
            if (playable is Playable.EpisodeOf) dismissUpNext(playable.show.id)
        }.onFailure { TorfilxLog.e(TAG, "Failed to remove $itemId from Continue Watching", it) }
    }

    /** Records that the show's current "up next" card, if it has one, was dismissed. */
    private suspend fun dismissUpNext(showId: String) {
        val finished = ShowPlayRules.lastFinished(catalog.seasons(showId), currentProgressMap()) ?: return
        showStateDao.upsert(
            ShowStateEntity(showId, dismissedAfterEpisodeId = finished.id, updatedAtMs = timeProvider.writeTimestampMs()),
        )
    }

    /**
     * Continue Watching, newest first.
     *
     * A film's card is the film, while it is part-watched. A show has at most one card, chosen by
     * [ShowPlayRules.continueWatching] from the most recent thing the viewer did with it:
     *
     * - an episode left part-way is resumed from its card, with its progress bar;
     * - after an episode is finished, the next one is "up next": a card for that episode with no bar;
     * - a show finished to its last episode, or never started, has no card.
     *
     * So an evening of episodes does not fill the row with the same show, and finishing one never
     * makes the show vanish from the row. An "up next" card the viewer removed stays away until they
     * finish another episode (see [remove]); an episode they start always brings the show back.
     *
     * Entries whose film or episode is not in the catalogue in use are skipped rather than shown as a
     * blank card, and so is a row stored under a show's own id, which nothing should ever write. The
     * catalogue can change while the app runs, so the row is re-evaluated whenever it does; the
     * progress itself is kept, and reappears if a later catalogue brings the title back.
     */
    fun observeContinueWatching(limit: Int = CONTINUE_WATCHING_LIMIT): Flow<List<MediaCard>> =
        combine(progressDao.observeEverything(), showStateDao.observeAll(), catalog.info) { rows, states, _ ->
            val progress = rows.associate { it.itemId to it.toDomain() }
            val dismissals = states.associateBy { it.showId }
            val entries = ArrayList<Pair<Long, MediaCard>>()
            val shows = LinkedHashSet<String>()

            progress.values.forEach { p ->
                when (val playable = catalog.playable(p.itemId)) {
                    null -> Unit
                    is Playable.Film -> if (ResumeRules.belongsInContinueWatching(p)) {
                        entries += p.updatedAtMs to MediaCard(item = playable.item, progress = p)
                    }
                    is Playable.EpisodeOf -> shows += playable.show.id
                }
            }
            shows.forEach { showId ->
                val show = catalog.item(showId) ?: return@forEach
                when (val entry = ShowPlayRules.continueWatching(show.seasons, progress)) {
                    null -> Unit
                    is ContinueWatching.InProgress -> entries += entry.sortKey to
                        MediaCard(item = show.item, episode = entry.episode, progress = progress[entry.episode.id])
                    // No progress on the card: the next episode has not been started, so no bar.
                    is ContinueWatching.UpNext -> if (!isDismissed(dismissals[showId], entry, progress)) {
                        entries += entry.sortKey to MediaCard(item = show.item, episode = entry.next)
                    }
                }
            }
            entries.sortedByDescending { it.first }.take(limit).map { it.second }
        }

    /**
     * True when [state] dismissed this very card: it names the episode the card follows, and that
     * episode has not been finished again since. A viewer who rewatches the same finale sees the card
     * again, which is the point of stamping the dismissal with a time.
     */
    private fun isDismissed(
        state: ShowStateEntity?,
        entry: ContinueWatching.UpNext,
        progress: Map<String, PlaybackProgress>,
    ): Boolean {
        state ?: return false
        if (state.dismissedAfterEpisodeId != entry.finished.id) return false
        val finishedAt = progress[entry.finished.id]?.updatedAtMs ?: return false
        return finishedAt <= state.updatedAtMs
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
