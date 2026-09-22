package com.torfilx.core.data.backup

import com.torfilx.core.common.di.Dispatcher
import com.torfilx.core.common.di.TorfilxDispatcher
import com.torfilx.core.data.database.MyListDao
import com.torfilx.core.data.database.MyListEntity
import com.torfilx.core.data.database.ProgressDao
import com.torfilx.core.data.database.ProgressEntity
import com.torfilx.core.data.database.ShowStateDao
import com.torfilx.core.data.database.ShowStateEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Export and restore the viewer's own state — Continue Watching positions, My List, and which
 * "up next" cards of shows were dismissed.
 *
 * The catalogue ships with the app and search history is transient, so those are deliberately left
 * out; what a viewer would actually miss after a reinstall or a stick swap is where they were up to
 * and what they had saved. Fire OS has no Google backup transport, so this is a manual, file-based
 * backup the user can copy off and back on (e.g. with adb), which is the only data-survival path a
 * sideloaded app without a backend can honestly offer.
 *
 * Format history — every older format must keep importing, because a backup file outlives the build
 * that wrote it:
 *  - 1: progress and My List.
 *  - 2: adds `showStates`. A format 1 file simply has none, and imports as it always did.
 */
@Singleton
class UserDataBackup @Inject constructor(
    private val progressDao: ProgressDao,
    private val myListDao: MyListDao,
    private val showStateDao: ShowStateDao,
    private val json: Json,
    @Dispatcher(TorfilxDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    @Serializable
    private data class Backup(
        // Absent means format 1: fields equal to their default are not written, and format 1 files
        // were written with this default. Export always passes FORMAT_VERSION, so it is written.
        val version: Int = 1,
        val progress: List<Progress> = emptyList(),
        val myList: List<MyListItem> = emptyList(),
        /** Format 2 onwards. */
        val showStates: List<ShowState> = emptyList(),
    )

    @Serializable
    private data class Progress(
        val itemId: String,
        val positionMs: Long,
        val durationMs: Long,
        val watched: Boolean,
        val updatedAtMs: Long,
    )

    @Serializable
    private data class MyListItem(val itemId: String, val addedAtMs: Long)

    @Serializable
    private data class ShowState(val showId: String, val dismissedAfterEpisodeId: String? = null, val updatedAtMs: Long)

    /** Serialises the current watch data to a JSON string. */
    suspend fun exportToJson(): String = withContext(ioDispatcher) {
        val backup = Backup(
            version = FORMAT_VERSION,
            progress = progressDao.all().map {
                Progress(it.itemId, it.positionMs, it.durationMs, it.watched, it.updatedAtMs)
            },
            myList = myListDao.all().map { MyListItem(it.itemId, it.addedAtMs) },
            showStates = showStateDao.all().map { ShowState(it.showId, it.dismissedAfterEpisodeId, it.updatedAtMs) },
        )
        json.encodeToString(Backup.serializer(), backup)
    }

    /**
     * Merges a previously exported JSON back in, of any format so far. Existing rows are upserted, so
     * a restore never loses newer local data than the backup — the more-recent position wins, and so
     * does the more-recent show state.
     */
    suspend fun importFromJson(text: String): Result = withContext(ioDispatcher) {
        val backup = json.decodeFromString(Backup.serializer(), text)
        backup.progress.forEach { p ->
            val existing = progressDao.get(p.itemId)
            if (existing == null || existing.updatedAtMs < p.updatedAtMs) {
                progressDao.upsert(
                    ProgressEntity(p.itemId, p.positionMs, p.durationMs, p.watched, p.updatedAtMs),
                )
            }
        }
        backup.myList.forEach { m ->
            if (myListDao.get(m.itemId) == null) myListDao.upsert(MyListEntity(m.itemId, m.addedAtMs))
        }
        backup.showStates.forEach { s ->
            val existing = showStateDao.get(s.showId)
            if (existing == null || existing.updatedAtMs < s.updatedAtMs) {
                showStateDao.upsert(ShowStateEntity(s.showId, s.dismissedAfterEpisodeId, s.updatedAtMs))
            }
        }
        Result(
            progressRestored = backup.progress.size,
            myListRestored = backup.myList.size,
            showStatesRestored = backup.showStates.size,
        )
    }

    data class Result(val progressRestored: Int, val myListRestored: Int, val showStatesRestored: Int = 0)

    private companion object {
        const val FORMAT_VERSION = 2
    }
}
