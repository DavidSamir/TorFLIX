package com.torfilx.core.testing

import com.torfilx.core.data.database.MyListDao
import com.torfilx.core.data.database.MyListEntity
import com.torfilx.core.data.database.ProgressDao
import com.torfilx.core.data.database.ProgressEntity
import com.torfilx.core.data.database.SearchHistoryDao
import com.torfilx.core.data.database.SearchHistoryEntity
import com.torfilx.core.data.database.ShowStateDao
import com.torfilx.core.data.database.ShowStateEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

// In-memory Room DAOs and catalogues for repository and view-model tests: plain JVM, no Robolectric.

class FakeProgressDao : ProgressDao {
    val rows = MutableStateFlow<List<ProgressEntity>>(emptyList())
    override suspend fun upsert(progress: ProgressEntity) {
        rows.value = rows.value.filterNot { it.itemId == progress.itemId } + progress
    }
    override suspend fun get(itemId: String): ProgressEntity? = rows.value.firstOrNull { it.itemId == itemId }
    override fun observe(itemId: String): Flow<ProgressEntity?> = rows.map { list -> list.firstOrNull { it.itemId == itemId } }
    override fun observeEverything(): Flow<List<ProgressEntity>> = rows
    override suspend fun all(): List<ProgressEntity> = rows.value
    override suspend fun delete(itemId: String) {
        rows.value = rows.value.filterNot { it.itemId == itemId }
    }
    override suspend fun clear() {
        rows.value = emptyList()
    }
    override suspend fun upsertAll(rows: List<ProgressEntity>) {
        rows.forEach { upsert(it) }
    }
    override suspend fun deleteIn(itemIds: List<String>) {
        rows.value = rows.value.filterNot { it.itemId in itemIds }
    }
}

class FakeMyListDao : MyListDao {
    val entries = MutableStateFlow<List<MyListEntity>>(emptyList())
    override suspend fun upsert(entry: MyListEntity) {
        entries.value = entries.value.filterNot { it.itemId == entry.itemId } + entry
    }
    override fun observeAll(): Flow<List<MyListEntity>> = entries
    override fun observeIds(): Flow<List<String>> = entries.map { list -> list.map { it.itemId } }
    override suspend fun all(): List<MyListEntity> = entries.value
    override suspend fun get(itemId: String): MyListEntity? = entries.value.firstOrNull { it.itemId == itemId }
    override suspend fun hardDelete(itemId: String) {
        entries.value = entries.value.filterNot { it.itemId == itemId }
    }
    override suspend fun clear() {
        entries.value = emptyList()
    }
}

class FakeSearchHistoryDao : SearchHistoryDao {
    override suspend fun insert(entry: SearchHistoryEntity) = Unit
    override fun observeRecent(limit: Int): Flow<List<SearchHistoryEntity>> = flowOf(emptyList())
    override suspend fun clear() = Unit
    override suspend fun trim(keep: Int) = Unit
}

class FakeShowStateDao : ShowStateDao {
    val states = MutableStateFlow<List<ShowStateEntity>>(emptyList())
    override suspend fun upsert(state: ShowStateEntity) {
        states.value = states.value.filterNot { it.showId == state.showId } + state
    }
    override suspend fun get(showId: String): ShowStateEntity? = states.value.firstOrNull { it.showId == showId }
    override fun observeAll(): Flow<List<ShowStateEntity>> = states
    override suspend fun all(): List<ShowStateEntity> = states.value
    override suspend fun clear() {
        states.value = emptyList()
    }
}
