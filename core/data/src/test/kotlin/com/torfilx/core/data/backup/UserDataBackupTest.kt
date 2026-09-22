package com.torfilx.core.data.backup

import com.google.common.truth.Truth.assertThat
import com.torfilx.core.data.database.MyListDao
import com.torfilx.core.data.database.MyListEntity
import com.torfilx.core.data.database.ProgressDao
import com.torfilx.core.data.database.ProgressEntity
import com.torfilx.core.data.database.ShowStateEntity
import com.torfilx.core.testing.FakeShowStateDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test

class UserDataBackupTest {

    private fun backupFor(p: ProgressDao, m: MyListDao, s: FakeShowStateDao = FakeShowStateDao()) =
        UserDataBackup(p, m, s, Json, UnconfinedTestDispatcher())

    @Test
    fun `export then import into an empty store restores everything`() = runTest {
        val source = FakeProgressDao().apply {
            rows["a"] = ProgressEntity("a", 1000, 5000, false, 10)
            rows["b"] = ProgressEntity("b", 5000, 5000, true, 20)
        }
        val sourceList = FakeMyListDao().apply { rows["x"] = MyListEntity("x", 100) }
        val json = backupFor(source, sourceList).exportToJson()

        val targetProgress = FakeProgressDao()
        val targetList = FakeMyListDao()
        val result = backupFor(targetProgress, targetList).importFromJson(json)

        assertThat(result.progressRestored).isEqualTo(2)
        assertThat(result.myListRestored).isEqualTo(1)
        assertThat(targetProgress.rows["a"]?.positionMs).isEqualTo(1000)
        assertThat(targetProgress.rows["b"]?.watched).isTrue()
        assertThat(targetList.rows).containsKey("x")
    }

    @Test
    fun `import never overwrites a newer local position`() = runTest {
        // Backup captured an older position (updatedAtMs = 100).
        val old = FakeProgressDao().apply { rows["a"] = ProgressEntity("a", 1000, 10_000, false, 100) }
        val backupJson = backupFor(old, FakeMyListDao()).exportToJson()

        // Local has since advanced (updatedAtMs = 200).
        val local = FakeProgressDao().apply { rows["a"] = ProgressEntity("a", 9000, 10_000, false, 200) }
        backupFor(local, FakeMyListDao()).importFromJson(backupJson)

        assertThat(local.rows["a"]?.positionMs).isEqualTo(9000) // newer local wins
    }

    @Test
    fun `import applies a backup position that is newer than local`() = runTest {
        val newer = FakeProgressDao().apply { rows["a"] = ProgressEntity("a", 8000, 10_000, false, 300) }
        val backupJson = backupFor(newer, FakeMyListDao()).exportToJson()

        val local = FakeProgressDao().apply { rows["a"] = ProgressEntity("a", 1000, 10_000, false, 100) }
        backupFor(local, FakeMyListDao()).importFromJson(backupJson)

        assertThat(local.rows["a"]?.positionMs).isEqualTo(8000) // newer backup applied
    }

    // --- Formats ---------------------------------------------------------------------------------

    @Test
    fun `a format 1 backup, written before shows existed, still imports`() = runTest {
        // Exactly what format 1 builds wrote: no version field (it equalled its default), no show states.
        val format1 = """{"progress":[{"itemId":"a","positionMs":1000,"durationMs":5000,"watched":false,"updatedAtMs":10}],""" +
            """"myList":[{"itemId":"x","addedAtMs":100}]}"""
        val progress = FakeProgressDao()
        val list = FakeMyListDao()
        val states = FakeShowStateDao()

        val result = backupFor(progress, list, states).importFromJson(format1)

        assertThat(result).isEqualTo(UserDataBackup.Result(progressRestored = 1, myListRestored = 1, showStatesRestored = 0))
        assertThat(progress.rows["a"]?.positionMs).isEqualTo(1000)
        assertThat(list.rows).containsKey("x")
        assertThat(states.states.value).isEmpty()

        // An explicit version 1 reads the same way.
        assertThat(backupFor(FakeProgressDao(), FakeMyListDao()).importFromJson("""{"version":1,"progress":[]}""").progressRestored)
            .isEqualTo(0)
    }

    @Test
    fun `export writes format 2, and a dismissed up-next card round-trips`() = runTest {
        val source = FakeShowStateDao().apply { upsert(ShowStateEntity("show-a-1950", "show-a-1950-s01e02", 50)) }
        val json = backupFor(FakeProgressDao(), FakeMyListDao(), source).exportToJson()
        assertThat(json).contains("\"version\":2")

        val target = FakeShowStateDao()
        val result = backupFor(FakeProgressDao(), FakeMyListDao(), target).importFromJson(json)

        assertThat(result.showStatesRestored).isEqualTo(1)
        assertThat(target.states.value).containsExactly(ShowStateEntity("show-a-1950", "show-a-1950-s01e02", 50))
    }

    @Test
    fun `import keeps a newer local show state, and applies an older local one's replacement`() = runTest {
        val backupJson = backupFor(
            FakeProgressDao(),
            FakeMyListDao(),
            FakeShowStateDao().apply {
                upsert(ShowStateEntity("newer-here", "from-backup", 100))
                upsert(ShowStateEntity("older-here", "from-backup", 100))
            },
        ).exportToJson()

        val local = FakeShowStateDao().apply {
            upsert(ShowStateEntity("newer-here", "local", 200))
            upsert(ShowStateEntity("older-here", "local", 50))
        }
        backupFor(FakeProgressDao(), FakeMyListDao(), local).importFromJson(backupJson)

        assertThat(local.states.value.associate { it.showId to it.dismissedAfterEpisodeId })
            .containsExactly("newer-here", "local", "older-here", "from-backup")
    }
}

private class FakeProgressDao : ProgressDao {
    val rows = mutableMapOf<String, ProgressEntity>()
    override suspend fun upsert(progress: ProgressEntity) { rows[progress.itemId] = progress }
    override suspend fun get(itemId: String): ProgressEntity? = rows[itemId]
    override fun observe(itemId: String): Flow<ProgressEntity?> = flowOf(rows[itemId])
    override fun observeEverything(): Flow<List<ProgressEntity>> = emptyFlow()
    override suspend fun all(): List<ProgressEntity> = rows.values.toList()
    override suspend fun delete(itemId: String) { rows.remove(itemId) }
    override suspend fun clear() { rows.clear() }
    override suspend fun upsertAll(rows: List<ProgressEntity>) { rows.forEach { upsert(it) } }
    override suspend fun deleteIn(itemIds: List<String>) { itemIds.forEach { rows.remove(it) } }
}

private class FakeMyListDao : MyListDao {
    val rows = mutableMapOf<String, MyListEntity>()
    override suspend fun upsert(entry: MyListEntity) { rows[entry.itemId] = entry }
    override fun observeAll(): Flow<List<MyListEntity>> = emptyFlow()
    override fun observeIds(): Flow<List<String>> = emptyFlow()
    override suspend fun all(): List<MyListEntity> = rows.values.toList()
    override suspend fun get(itemId: String): MyListEntity? = rows[itemId]
    override suspend fun hardDelete(itemId: String) { rows.remove(itemId) }
    override suspend fun clear() { rows.clear() }
}
