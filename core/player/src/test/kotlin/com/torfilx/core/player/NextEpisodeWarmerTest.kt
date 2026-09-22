package com.torfilx.core.player

import com.google.common.truth.Truth.assertThat
import com.torfilx.core.model.FileSelection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NextEpisodeWarmerTest {

    private class Engine {
        val streamed = mutableListOf<String>()
        val released = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        var playing: String? = null
        var metadataMs = 5_000L
        var fails = false
    }

    private fun target(id: String) = NextEpisodeWarmer.Target(
        magnet = "magnet:?xt=urn:btih:hash-$id",
        infoHash = "hash-$id",
        selection = FileSelection.LargestVideo,
        displayName = "Show · $id",
    )

    private fun TestScope.warmer(engine: Engine, resolvable: Set<String>? = null) = NextEpisodeWarmer(
        scope = backgroundScope,
        resolve = { id -> if (resolvable == null || id in resolvable) target(id) else null },
        isPlaying = { it == engine.playing },
        stream = { t ->
            delay(engine.metadataMs)
            if (engine.fails) error("no peers")
            engine.streamed += t.infoHash
        },
        release = { engine.released += it },
        discard = { engine.discarded += it },
    )

    private fun TestScope.settle() {
        advanceTimeBy(60_000)
        runCurrent()
    }

    @Test
    fun `the warmed episode is handed over to its open, not released`() = runTest {
        val engine = Engine()
        val warmer = warmer(engine)
        warmer.warm("e2")
        settle()

        assertThat(warmer.claim("e2")).isEqualTo("hash-e2")
        assertThat(engine.streamed).containsExactly("hash-e2")
        assertThat(engine.released).isEmpty()
        assertThat(warmer.warmingId).isNull()
    }

    @Test
    fun `an open that arrives mid-fetch waits for it and takes it over`() = runTest {
        val engine = Engine()
        val warmer = warmer(engine)
        warmer.warm("e2")
        advanceTimeBy(1_000)

        val claimed = async { warmer.claim("e2") }
        runCurrent()
        assertThat(claimed.isCompleted).isFalse()
        settle()

        assertThat(claimed.await()).isEqualTo("hash-e2")
        assertThat(engine.released).isEmpty()
    }

    @Test
    fun `backing out after the fetch releases the torrent`() = runTest {
        val engine = Engine()
        val warmer = warmer(engine)
        warmer.warm("e2")
        settle()

        warmer.coolDown()
        settle()
        assertThat(engine.released).containsExactly("hash-e2")
    }

    @Test
    fun `backing out mid-fetch releases the torrent the moment it arrives, never cancelling the fetch`() = runTest {
        val engine = Engine()
        val warmer = warmer(engine)
        warmer.warm("e2")
        advanceTimeBy(1_000)

        warmer.coolDown()
        runCurrent()
        assertThat(engine.released).isEmpty()

        settle()
        assertThat(engine.streamed).containsExactly("hash-e2")
        assertThat(engine.released).containsExactly("hash-e2")
    }

    @Test
    fun `backing out before the source is known fetches nothing`() = runTest {
        val engine = Engine()
        val warmer = warmer(engine)
        warmer.warm("e2")
        warmer.coolDown()
        settle()
        assertThat(engine.streamed).isEmpty()
        assertThat(engine.released).isEmpty()
    }

    @Test
    fun `opening a different episode releases what was warmed`() = runTest {
        val engine = Engine()
        val warmer = warmer(engine)
        warmer.warm("e2")
        settle()

        assertThat(warmer.claim("e1")).isNull()
        settle()
        assertThat(engine.released).containsExactly("hash-e2")
    }

    @Test
    fun `the torrent already playing is never fetched again, so a season pack's file is not switched`() = runTest {
        val engine = Engine().apply { playing = "hash-e2" }
        val warmer = warmer(engine)
        warmer.warm("e2")
        settle()

        assertThat(engine.streamed).isEmpty()
        assertThat(warmer.claim("e2")).isNull()
        warmer.coolDown()
        settle()
        assertThat(engine.released).isEmpty()
    }

    @Test
    fun `a failed fetch is discarded and the open fetches afresh`() = runTest {
        val engine = Engine().apply { fails = true }
        val warmer = warmer(engine)
        warmer.warm("e2")
        settle()

        assertThat(engine.discarded).containsExactly("hash-e2")
        assertThat(warmer.claim("e2")).isNull()
        assertThat(engine.released).isEmpty()
    }

    @Test
    fun `an episode with nothing to warm is left alone`() = runTest {
        val engine = Engine()
        val warmer = warmer(engine, resolvable = emptySet())
        warmer.warm("e2")
        settle()
        assertThat(engine.streamed).isEmpty()
        assertThat(warmer.claim("e2")).isNull()
    }

    @Test
    fun `warming the same episode twice fetches once, and another episode replaces it`() = runTest {
        val engine = Engine()
        val warmer = warmer(engine)
        warmer.warm("e2")
        warmer.warm("e2")
        settle()
        assertThat(engine.streamed).containsExactly("hash-e2")

        warmer.warm("e3")
        settle()
        assertThat(engine.released).containsExactly("hash-e2")
        assertThat(warmer.claim("e3")).isEqualTo("hash-e3")
    }
}
