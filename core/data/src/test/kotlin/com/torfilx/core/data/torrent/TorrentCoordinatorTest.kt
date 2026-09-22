package com.torfilx.core.data.torrent

import com.torfilx.core.data.repository.ContributionRepository
import com.torfilx.core.data.settings.SettingsRepository
import com.torfilx.core.model.AppSettings
import com.torfilx.core.torrent.LibTorrentEngine
import com.torfilx.core.torrent.TorrentEngine
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * What happens to a title's data when the viewer leaves the player.
 *
 * The seeding-off case is the one that matters: removing the torrent while leaving its files behind
 * made them invisible to the storage budget, so an evening of episodes filled the disk with data
 * nothing could reclaim.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TorrentCoordinatorTest {

    private val engine = mockk<TorrentEngine>(relaxed = true) {
        every { torrents } returns emptyFlow()
        every { stats } returns emptyFlow()
    }

    private fun TestScope.coordinator(seeding: Boolean): TorrentCoordinator {
        val settings = mockk<SettingsRepository>(relaxed = true) {
            every { seedingEnabled } returns MutableStateFlow(seeding)
            // No consent: the coordinator must not try to warm a session in a unit test.
            every { sharingConsent } returns flowOf(false)
            every { storageFraction } returns emptyFlow()
            every { this@mockk.settings } returns flowOf(AppSettings())
        }
        return TorrentCoordinator(
            engine = engine,
            libTorrentEngine = mockk<LibTorrentEngine>(relaxed = true),
            settingsRepository = settings,
            contributionRepository = mockk<ContributionRepository>(relaxed = true),
            scope = backgroundScope,
        )
    }

    @Test
    fun `with seeding off, leaving the player removes the torrent and deletes its data`() = runTest {
        coordinator(seeding = false).stopStreaming(INFO_HASH)

        coVerify(exactly = 1) { engine.remove(INFO_HASH, deleteData = true) }
        coVerify(exactly = 0) { engine.remove(INFO_HASH, deleteData = false) }
        coVerify(exactly = 0) { engine.stopStreaming(any()) }
    }

    @Test
    fun `with seeding on, leaving the player keeps the torrent so it can seed and be evicted later`() = runTest {
        coordinator(seeding = true).stopStreaming(INFO_HASH)

        coVerify(exactly = 1) { engine.stopStreaming(INFO_HASH) }
        coVerify(exactly = 0) { engine.remove(any(), any()) }
    }

    private companion object {
        const val INFO_HASH = "0697bc07ebc5914085c2a3bce646509086bf6265"
    }
}
