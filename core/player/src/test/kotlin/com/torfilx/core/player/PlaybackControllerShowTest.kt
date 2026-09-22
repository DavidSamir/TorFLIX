package com.torfilx.core.player

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.truth.Truth.assertThat
import com.torfilx.core.catalogue.format.CatalogEntryDto
import com.torfilx.core.catalogue.format.CatalogIds
import com.torfilx.core.catalogue.format.CatalogMagnetDto
import com.torfilx.core.catalogue.testing.TestCatalogues
import com.torfilx.core.data.repository.MediaRepository
import com.torfilx.core.data.repository.MyListRepository
import com.torfilx.core.data.repository.ProgressRepository
import com.torfilx.core.data.settings.SettingsRepository
import com.torfilx.core.data.torrent.TorrentCoordinator
import com.torfilx.core.model.AppSettings
import com.torfilx.core.model.DeviceCapabilities
import com.torfilx.core.model.EpisodeFileMatcher
import com.torfilx.core.model.FileSelection
import com.torfilx.core.model.MagnetLink
import com.torfilx.core.player.capability.DeviceCapabilitiesProvider
import com.torfilx.core.testing.FakeMyListDao
import com.torfilx.core.testing.FakeProgressDao
import com.torfilx.core.testing.FakeShowStateDao
import com.torfilx.core.testing.FakeSearchHistoryDao
import com.torfilx.core.testing.FakeTimeProvider
import com.torfilx.core.testing.MainDispatcherRule
import com.torfilx.core.testing.inMemoryCatalog
import com.torfilx.core.torrent.TorrentStream
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The playback controller with shows: which episode a show plays, what an episode is called on
 * screen, and the hand-over from one episode to the next — over the real catalogue and progress
 * store, with the player and the torrent engine mocked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerShowTest {

    @get:Rule
    val main = MainDispatcherRule(StandardTestDispatcher())

    @get:Rule
    val tmp = TemporaryFolder()

    private val entries: List<CatalogEntryDto> = CatalogIds.pin(
        TestCatalogues.entries(1).map { it.copy(id = null) } +
            TestCatalogues.show(title = "Twilight", seasons = 1, episodesPerSeason = 3) +
            CatalogEntryDto(
                type = CatalogEntryDto.TYPE_SHOW,
                title = "Lost",
                seasons = listOf(
                    com.torfilx.core.catalogue.format.CatalogSeasonDto(
                        number = 1,
                        episodes = listOf(
                            com.torfilx.core.catalogue.format.CatalogEpisodeDto(
                                number = 1,
                                magnets = listOf(CatalogMagnetDto(magnet = "magnet:?xt=urn:btih:broken")),
                            ),
                        ),
                    ),
                ),
            ),
    )
    private val filmId = entries[0].id!!
    private val show = entries[1]
    private val showId = show.id!!
    private val episodes = show.seasons.single().episodes
    private val e1 = episodes[0].id!!
    private val e2 = episodes[1].id!!
    private val e3 = episodes[2].id!!
    private val unplayableShowId = entries[2].id!!

    private fun hashOf(entry: com.torfilx.core.catalogue.format.CatalogEpisodeDto) =
        MagnetLink.infoHashOf(entry.magnets.single().magnet)!!

    private val listener = slot<Player.Listener>()
    private var position = 0L
    private val exo = mockk<ExoPlayer>(relaxed = true) {
        every { addListener(capture(listener)) } just Runs
        every { currentPosition } answers { position }
        every { duration } returns C.TIME_UNSET
        every { currentTracks } returns Tracks.EMPTY
    }
    private val streamed = mutableListOf<Triple<String, FileSelection, String?>>()
    private val coordinator = mockk<TorrentCoordinator>(relaxed = true) {
        every { torrents } returns emptyFlow()
        coEvery { stream(any(), any(), any()) } answers {
            val magnet = firstArg<String>()
            streamed += Triple(magnet, secondArg(), thirdArg())
            TorrentStream(MagnetLink.infoHashOf(magnet)!!, "http://127.0.0.1:1/x", "x.mkv", 1)
        }
    }
    private val autoplay = MutableStateFlow(true)
    private val progressDao = FakeProgressDao()

    private fun TestScope.controller(): PlaybackController {
        val catalog = inMemoryCatalog(entries, tmp.root)
        val time = FakeTimeProvider()
        val progress = ProgressRepository(progressDao, catalog, time, FakeShowStateDao())
        val media = MediaRepository(catalog, FakeSearchHistoryDao(), progress, MyListRepository(FakeMyListDao(), time), time, main.dispatcher)
        val settings = mockk<SettingsRepository>(relaxed = true) {
            every { this@mockk.settings } answers { MutableStateFlow(AppSettings(autoplayNextEpisode = autoplay.value)) }
        }
        return PlaybackController(
            playerFactory = mockk { every { create(any()) } returns exo },
            playbackInfoRepository = PlaybackInfoRepository(
                catalog,
                mockk<DeviceCapabilitiesProvider> { every { capabilities() } returns DeviceCapabilities.CONSERVATIVE },
            ),
            mediaRepository = media,
            progressRepository = progress,
            settingsRepository = settings,
            torrentCoordinator = coordinator,
            catalog = catalog,
            scope = backgroundScope,
            ioDispatcher = main.dispatcher,
        )
    }

    /** The episode reaches its end, as ExoPlayer reports it. */
    private fun TestScope.endPlayback() {
        position = 25 * MINUTE
        listener.captured.onPlaybackStateChanged(Player.STATE_ENDED)
        runCurrent()
    }

    @Test
    fun `a show's id plays its next-up episode, named under the show`() = runTest(main.dispatcher) {
        val c = controller()
        c.open(PlaybackRequest(showId))

        val state = c.state.value
        assertThat(state.error).isNull()
        assertThat(state.episode?.id).isEqualTo(e1)
        assertThat(state.title).isEqualTo("Twilight")
        assertThat(state.subtitle).isEqualTo("S1 E1 · Episode 1 of season 1")
        assertThat(streamed.single().first).contains(hashOf(episodes[0]))
    }

    @Test
    fun `an episode streams with its file hint and a name the sharing figures can show`() = runTest(main.dispatcher) {
        val c = controller()
        c.open(PlaybackRequest(e2))

        val (_, hint, name) = streamed.single()
        assertThat(hint).isEqualTo(
            FileSelection.PreferEpisode(EpisodeFileMatcher.Target(season = 1, episode = 2, ordinal = 1, episodesInSeason = 3)),
        )
        assertThat(name).isEqualTo("Twilight · S1 E2")
    }

    @Test
    fun `a show with nothing playable says so instead of playing`() = runTest(main.dispatcher) {
        val c = controller()
        c.open(PlaybackRequest(unplayableShowId))
        assertThat(c.state.value.error).isInstanceOf(PlaybackError.Unsupported::class.java)
        assertThat(streamed).isEmpty()
    }

    @Test
    fun `an episode ending counts down, then plays the next in place and stops the old torrent`() = runTest(main.dispatcher) {
        val c = controller()
        c.open(PlaybackRequest(e1))
        endPlayback()

        assertThat(c.state.value.endCard).isEqualTo(EndCard.Countdown(c.state.value.endCard.let { (it as EndCard.Countdown).next }, 10))
        assertThat((c.state.value.endCard as EndCard.Countdown).next.id).isEqualTo(e2)
        assertThat(progressDao.rows.value.first { it.itemId == e1 }.watched).isTrue()

        advanceTimeBy(10_500)
        runCurrent()

        assertThat(c.state.value.episode?.id).isEqualTo(e2)
        assertThat(c.state.value.endCard).isNull()
        assertThat(streamed.map { it.first }.last()).contains(hashOf(episodes[1]))
        coVerify { coordinator.stopStreaming(hashOf(episodes[0])) }
    }

    @Test
    fun `Play now keeps the viewer's speed and aspect, and a new title starts afresh`() = runTest(main.dispatcher) {
        val c = controller()
        c.open(PlaybackRequest(e1))
        c.setSpeed(1.5f)
        c.setAspectMode(AspectMode.ZOOM)
        endPlayback()

        c.playNext()
        runCurrent()

        assertThat(c.state.value.episode?.id).isEqualTo(e2)
        assertThat(c.state.value.playbackSpeed).isEqualTo(1.5f)
        assertThat(c.state.value.aspectMode).isEqualTo(AspectMode.ZOOM)
        verify { exo.setPlaybackSpeed(1.5f) }

        c.stop(release = false)
        c.open(PlaybackRequest(filmId))
        assertThat(c.state.value.playbackSpeed).isEqualTo(1f)
        assertThat(c.state.value.aspectMode).isEqualTo(AspectMode.FIT)
    }

    @Test
    fun `with autoplay off the next episode waits on its card`() = runTest(main.dispatcher) {
        autoplay.value = false
        val c = controller()
        c.open(PlaybackRequest(e1))
        endPlayback()
        advanceTimeBy(30_000)
        runCurrent()

        assertThat(c.state.value.endCard).isEqualTo(EndCard.Next(c.state.value.endCard.let { (it as EndCard.Next).next }))
        assertThat(streamed).hasSize(1)
    }

    @Test
    fun `leaving the app during the countdown stops it, and lets the warmed episode go`() = runTest(main.dispatcher) {
        val c = controller()
        c.open(PlaybackRequest(e1))
        endPlayback()
        advanceTimeBy(3_000)
        c.onBackground()
        advanceTimeBy(30_000)
        runCurrent()

        assertThat(c.state.value.endCard).isInstanceOf(EndCard.Next::class.java)
        // The countdown fetched the next episode ahead of time; leaving released it and played nothing.
        assertThat(streamed.map { it.third }).containsExactly("Twilight · S1 E1", "Twilight · S1 E2").inOrder()
        coVerify(exactly = 1) { coordinator.stopStreaming(hashOf(episodes[1])) }
        assertThat(c.state.value.episode?.id).isEqualTo(e1)
    }

    @Test
    fun `leaving the player during the countdown ends it and lets the warmed episode go`() = runTest(main.dispatcher) {
        val c = controller()
        c.open(PlaybackRequest(e1))
        endPlayback()
        c.stop(release = false)
        advanceTimeBy(30_000)
        runCurrent()

        assertThat(streamed.map { it.third }).containsExactly("Twilight · S1 E1", "Twilight · S1 E2").inOrder()
        coVerify(exactly = 1) { coordinator.stopStreaming(hashOf(episodes[0])) }
        coVerify(exactly = 1) { coordinator.stopStreaming(hashOf(episodes[1])) }
        assertThat(c.state.value.endCard).isNull()
    }

    @Test
    fun `the countdown's warmed torrent is the one the next episode plays, and it is not released`() = runTest(main.dispatcher) {
        val c = controller()
        c.open(PlaybackRequest(e1))
        endPlayback()
        advanceTimeBy(30_000)
        runCurrent()

        assertThat(c.state.value.episode?.id).isEqualTo(e2)
        // Warmed during the countdown, then asked for again by the open, which the engine answers
        // from the torrent it already has.
        assertThat(streamed.map { it.third }).containsExactly("Twilight · S1 E1", "Twilight · S1 E2", "Twilight · S1 E2").inOrder()
        coVerify(exactly = 0) { coordinator.stopStreaming(hashOf(episodes[1])) }
        coVerify(exactly = 1) { coordinator.stopStreaming(hashOf(episodes[0])) }
    }

    @Test
    fun `with autoplay off nothing is fetched ahead of the viewer's choice`() = runTest(main.dispatcher) {
        autoplay.value = false
        val c = controller()
        c.open(PlaybackRequest(e1))
        endPlayback()
        advanceTimeBy(30_000)
        runCurrent()

        assertThat(c.state.value.endCard).isInstanceOf(EndCard.Next::class.java)
        assertThat(streamed).hasSize(1)
    }

    @Test
    fun `the last episode ends the show, and Watch again starts from its first episode at the start`() = runTest(main.dispatcher) {
        val c = controller()
        c.open(PlaybackRequest(e3))
        endPlayback()

        val card = c.state.value.endCard as EndCard.EndOfShow
        assertThat(card.showTitle).isEqualTo("Twilight")
        assertThat(card.first?.id).isEqualTo(e1)

        c.watchAgain()
        runCurrent()
        assertThat(c.state.value.episode?.id).isEqualTo(e1)
        verify { exo.setMediaItem(any(), 0L) }
    }

    @Test
    fun `a film just ends, marked watched, with no card`() = runTest(main.dispatcher) {
        val c = controller()
        c.open(PlaybackRequest(filmId))
        endPlayback()
        advanceTimeBy(30_000)
        runCurrent()

        assertThat(c.state.value.endCard).isNull()
        assertThat(progressDao.rows.value.first { it.itemId == filmId }.watched).isTrue()
    }

    private companion object {
        const val MINUTE = 60_000L
    }

    // --- The remote's next key ------------------------------------------------------------------

    @Test
    fun `next mid-episode pauses it and offers the next one, and Keep watching goes back`() = runTest(main.dispatcher) {
        val c = controller()
        c.open(PlaybackRequest(e1))
        position = 5 * MINUTE
        c.onMediaNext()
        runCurrent()

        assertThat(c.state.value.endCard).isEqualTo(EndCard.Next(c.state.value.endCard.let { (it as EndCard.Next).next }, midEpisode = true))
        assertThat((c.state.value.endCard as EndCard.Next).next.id).isEqualTo(e2)
        verify { exo.pause() }
        assertThat(progressDao.rows.value.single { it.itemId == e1 }.positionMs).isEqualTo(5 * MINUTE)

        c.dismissNextEpisodeCard()
        assertThat(c.state.value.endCard).isNull()
        verify { exo.play() }
        assertThat(c.state.value.episode?.id).isEqualTo(e1)
    }

    @Test
    fun `next twice plays the next episode`() = runTest(main.dispatcher) {
        val c = controller()
        c.open(PlaybackRequest(e1))
        c.onMediaNext()
        runCurrent()
        c.onMediaNext()
        advanceTimeBy(1_000)
        runCurrent()

        assertThat(c.state.value.episode?.id).isEqualTo(e2)
        assertThat(c.state.value.endCard).isNull()
    }

    @Test
    fun `next during the countdown plays at once`() = runTest(main.dispatcher) {
        val c = controller()
        c.open(PlaybackRequest(e1))
        endPlayback()
        c.onMediaNext()
        advanceTimeBy(1_000)
        runCurrent()
        assertThat(c.state.value.episode?.id).isEqualTo(e2)
    }

    @Test
    fun `next does nothing for a film or the last episode`() = runTest(main.dispatcher) {
        val c = controller()
        c.open(PlaybackRequest(filmId))
        c.onMediaNext()
        runCurrent()
        assertThat(c.state.value.endCard).isNull()

        c.open(PlaybackRequest(e3))
        c.onMediaNext()
        runCurrent()
        assertThat(c.state.value.endCard).isNull()
        verify(exactly = 0) { exo.pause() }
    }
}
