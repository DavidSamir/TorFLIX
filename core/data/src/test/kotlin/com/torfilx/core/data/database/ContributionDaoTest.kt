package com.torfilx.core.data.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.SQLiteMode

/**
 * The streamed totals, written on a SQLite as old as the one a Fire TV runs.
 *
 * Robolectric's legacy SQLite is 3.8, the generation Fire OS 5 and 6 ship. The totals used to be
 * written with `INSERT … ON CONFLICT … DO UPDATE`, which needs 3.24: on a television every write
 * failed and "Data streamed" in Settings stayed at zero.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class ContributionDaoTest {

    private lateinit var database: TorfilxDatabase
    private val dao get() = database.contributionDao()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TorfilxDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `this test runs on a SQLite older than the upsert syntax, as a Fire TV does`() {
        val version = database.openHelper.readableDatabase.query("SELECT sqlite_version()").use { cursor ->
            cursor.moveToFirst()
            cursor.getString(0)
        }
        val (major, minor) = version.split('.').map { it.toInt() }
        assertThat(major * 100 + minor).isLessThan(UPSERT_ARRIVED)
    }

    @Test
    fun `a title's totals start on first sight and add up after it`() = runTest {
        dao.accumulate("hash", title = "", uploaded = 100, downloaded = 1_000, sizeBytes = 0, nowMs = 10, onDisk = true)
        dao.accumulate("hash", title = "The Kid", uploaded = 50, downloaded = 500, sizeBytes = 4_000, nowMs = 20, onDisk = false)
        dao.accumulate("other", title = "Nosferatu", uploaded = 7, downloaded = 9, sizeBytes = 1, nowMs = 30, onDisk = true)

        val rows = dao.observeAll().first().associateBy { it.infoHash }
        assertThat(rows.keys).containsExactly("hash", "other")
        with(rows.getValue("hash")) {
            assertThat(title).isEqualTo("The Kid")
            assertThat(uploadedBytes).isEqualTo(150)
            assertThat(downloadedBytes).isEqualTo(1_500)
            assertThat(sizeBytes).isEqualTo(4_000)
            assertThat(firstSharedAtMs).isEqualTo(10)
            assertThat(lastActiveAtMs).isEqualTo(20)
            assertThat(stillOnDisk).isFalse()
        }
    }

    @Test
    fun `a title seen with an empty name keeps the name it had`() = runTest {
        dao.accumulate("hash", title = "The Kid", uploaded = 1, downloaded = 1, sizeBytes = 10, nowMs = 1, onDisk = true)
        dao.accumulate("hash", title = "", uploaded = 1, downloaded = 1, sizeBytes = 5, nowMs = 2, onDisk = true)

        val row = dao.observeAll().first().single()
        assertThat(row.title).isEqualTo("The Kid")
        assertThat(row.sizeBytes).isEqualTo(10)
    }

    @Test
    fun `each day's totals start on first sight and add up after it`() = runTest {
        dao.accumulateDay(epochDay = 5, uploaded = 100, downloaded = 1_000)
        dao.accumulateDay(epochDay = 5, uploaded = 50, downloaded = 500)
        dao.accumulateDay(epochDay = 6, uploaded = 1, downloaded = 2)

        val days = dao.observeDaysSince(0).first().map { Triple(it.epochDay, it.uploadedBytes, it.downloadedBytes) }
        assertThat(days).containsExactly(Triple(5L, 150L, 1_500L), Triple(6L, 1L, 2L)).inOrder()
    }

    private companion object {
        /** SQLite 3.24, written as major * 100 + minor. */
        const val UPSERT_ARRIVED = 324
    }
}
