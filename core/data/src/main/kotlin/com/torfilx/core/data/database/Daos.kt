package com.torfilx.core.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProgressDao {
    @Upsert
    suspend fun upsert(progress: ProgressEntity)

    @Query("SELECT * FROM progress WHERE itemId = :itemId")
    suspend fun get(itemId: String): ProgressEntity?

    @Query("SELECT * FROM progress WHERE itemId = :itemId")
    fun observe(itemId: String): Flow<ProgressEntity?>

    @Query("SELECT * FROM progress")
    fun observeEverything(): Flow<List<ProgressEntity>>

    @Query("SELECT * FROM progress")
    suspend fun all(): List<ProgressEntity>

    @Query("DELETE FROM progress WHERE itemId = :itemId")
    suspend fun delete(itemId: String)

    @Query("DELETE FROM progress")
    suspend fun clear()

    /** Several rows in one transaction: a season marked watched is all of it or none of it. */
    @Upsert
    suspend fun upsertAll(rows: List<ProgressEntity>)

    @Query("DELETE FROM progress WHERE itemId IN (:itemIds)")
    suspend fun deleteIn(itemIds: List<String>)

    /**
     * Deletes [itemIds] in one transaction, in chunks.
     *
     * Chunked because SQLite on the older Fire OS releases allows at most 999 bound parameters in one
     * statement, and a long show has more episodes than that.
     */
    @Transaction
    suspend fun deleteAll(itemIds: List<String>) {
        itemIds.chunked(PROGRESS_DELETE_CHUNK).forEach { deleteIn(it) }
    }
}

/** Well under the 999 bound parameters SQLite allows per statement on older Fire OS. */
internal const val PROGRESS_DELETE_CHUNK = 500

/** Per-show state; see [ShowStateEntity]. */
@Dao
interface ShowStateDao {
    @Upsert
    suspend fun upsert(state: ShowStateEntity)

    @Query("SELECT * FROM show_state WHERE showId = :showId")
    suspend fun get(showId: String): ShowStateEntity?

    @Query("SELECT * FROM show_state")
    fun observeAll(): Flow<List<ShowStateEntity>>

    @Query("SELECT * FROM show_state")
    suspend fun all(): List<ShowStateEntity>

    @Query("DELETE FROM show_state")
    suspend fun clear()
}

@Dao
interface MyListDao {
    @Upsert
    suspend fun upsert(entry: MyListEntity)

    @Query("SELECT * FROM my_list ORDER BY addedAtMs DESC")
    fun observeAll(): Flow<List<MyListEntity>>

    @Query("SELECT itemId FROM my_list")
    fun observeIds(): Flow<List<String>>

    @Query("SELECT * FROM my_list")
    suspend fun all(): List<MyListEntity>

    @Query("SELECT * FROM my_list WHERE itemId = :itemId")
    suspend fun get(itemId: String): MyListEntity?

    @Query("DELETE FROM my_list WHERE itemId = :itemId")
    suspend fun hardDelete(itemId: String)

    @Query("DELETE FROM my_list")
    suspend fun clear()
}

@Dao
interface SearchHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: SearchHistoryEntity)

    @Query("SELECT * FROM search_history ORDER BY searchedAtMs DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SearchHistoryEntity>>

    @Query("DELETE FROM search_history")
    suspend fun clear()

    /** Records a search and trims the history to the most recent [keep] entries. */
    @Transaction
    suspend fun record(query: String, atMs: Long, keep: Int) {
        insert(SearchHistoryEntity(query, atMs))
        trim(keep)
    }

    @Query(
        """
        DELETE FROM search_history WHERE query NOT IN (
            SELECT query FROM search_history ORDER BY searchedAtMs DESC LIMIT :keep
        )
        """,
    )
    suspend fun trim(keep: Int)
}

@Dao
interface ContributionDao {

    /**
     * Adds a delta to a title's lifetime totals, creating the row on first sight.
     *
     * Written as an upsert in SQL rather than read-modify-write in Kotlin so the accumulation is
     * atomic: the fold runs from a background tick while the contribution screen may be reading, and
     * a lost update here silently loses someone's shared bytes.
     */
    @Query(
        """
        INSERT INTO contribution (
            infoHash, title, uploadedBytes, downloadedBytes, sizeBytes,
            firstSharedAtMs, lastActiveAtMs, stillOnDisk
        )
        VALUES (:infoHash, :title, :uploaded, :downloaded, :sizeBytes, :nowMs, :nowMs, :onDisk)
        ON CONFLICT(infoHash) DO UPDATE SET
            uploadedBytes = uploadedBytes + :uploaded,
            downloadedBytes = downloadedBytes + :downloaded,
            -- Size and title are refreshed because the first sighting may predate metadata arriving.
            sizeBytes = MAX(sizeBytes, :sizeBytes),
            title = CASE WHEN :title != '' THEN :title ELSE title END,
            lastActiveAtMs = :nowMs,
            stillOnDisk = :onDisk
        """,
    )
    suspend fun accumulate(
        infoHash: String,
        title: String,
        uploaded: Long,
        downloaded: Long,
        sizeBytes: Long,
        nowMs: Long,
        onDisk: Boolean,
    )

    @Query("SELECT * FROM contribution ORDER BY uploadedBytes DESC")
    fun observeAll(): Flow<List<ContributionEntity>>

    /** Marks everything as gone from disk; the caller then re-marks what is actually present. */
    @Query("UPDATE contribution SET stillOnDisk = 0")
    suspend fun clearOnDiskFlags()

    @Query("UPDATE contribution SET stillOnDisk = 1 WHERE infoHash IN (:infoHashes)")
    suspend fun markOnDisk(infoHashes: List<String>)

    @Query("DELETE FROM contribution")
    suspend fun clear()

    @Query(
        """
        INSERT INTO contribution_day (epochDay, uploadedBytes, downloadedBytes)
        VALUES (:epochDay, :uploaded, :downloaded)
        ON CONFLICT(epochDay) DO UPDATE SET
            uploadedBytes = uploadedBytes + :uploaded,
            downloadedBytes = downloadedBytes + :downloaded
        """,
    )
    suspend fun accumulateDay(epochDay: Long, uploaded: Long, downloaded: Long)

    @Query("SELECT * FROM contribution_day WHERE epochDay >= :sinceEpochDay ORDER BY epochDay")
    fun observeDaysSince(sinceEpochDay: Long): Flow<List<ContributionDayEntity>>

    /** Keeps the rollup bounded; the chart never looks further back than this. */
    @Query("DELETE FROM contribution_day WHERE epochDay < :beforeEpochDay")
    suspend fun pruneDaysBefore(beforeEpochDay: Long)

    @Query("DELETE FROM contribution_day")
    suspend fun clearDays()
}
