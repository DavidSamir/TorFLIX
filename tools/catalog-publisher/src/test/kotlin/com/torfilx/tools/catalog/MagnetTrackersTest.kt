package com.torfilx.tools.catalog

import com.google.common.truth.Truth.assertThat
import com.torfilx.core.catalogue.format.CatalogEntryDto
import com.torfilx.core.catalogue.format.CatalogMagnetDto
import com.torfilx.core.catalogue.testing.TestCatalogues
import com.torfilx.core.model.MagnetLink
import org.junit.Test
import java.net.URLEncoder

/** `build --strip-trackers`: fewer trackers per magnet, and nothing else about the magnet changes. */
class MagnetTrackersTest {

    private val hash = "0697bc07ebc5914085c2a3bce646509086bf6265"
    private fun tr(url: String) = "tr=" + URLEncoder.encode(url, "UTF-8")

    private val appTracker = OfflineCommands.DEFAULT_TRACKERS[2]
    private val magnet = "magnet:?xt=urn:btih:$hash&dn=The+Kid" +
        "&${tr("udp://dead.one:1/announce")}&${tr("udp://dead.two:2/announce")}" +
        "&${tr(appTracker)}&${tr("udp://dead.three:3/announce")}"

    @Test
    fun `keeps the requested number of trackers, the app's own first`() {
        val stripped = MagnetTrackers.strip(magnet, keep = 2, preferred = OfflineCommands.DEFAULT_TRACKERS)

        val trackers = stripped.split('&').filter { it.startsWith("tr=") }
        // The app's own tracker is chosen first; the kept ones stay in the magnet's original order.
        assertThat(trackers).containsExactly(tr("udp://dead.one:1/announce"), tr(appTracker)).inOrder()
    }

    @Test
    fun `the info hash, the name and the order of everything else are untouched`() {
        val stripped = MagnetTrackers.strip(magnet, keep = 1, preferred = emptyList())
        assertThat(stripped).startsWith("magnet:?xt=urn:btih:$hash&dn=The+Kid&")
        assertThat(MagnetLink.infoHashOf(stripped)).isEqualTo(hash)
        assertThat(stripped.length).isLessThan(magnet.length)
    }

    @Test
    fun `a magnet with few enough trackers, or none, or no magnet at all, comes back as it was`() {
        val few = "magnet:?xt=urn:btih:$hash&${tr(appTracker)}"
        assertThat(MagnetTrackers.strip(few, keep = 2, preferred = emptyList())).isEqualTo(few)
        assertThat(MagnetTrackers.strip("magnet:?xt=urn:btih:$hash", keep = 2, preferred = emptyList()))
            .isEqualTo("magnet:?xt=urn:btih:$hash")
        assertThat(MagnetTrackers.strip("not a magnet", keep = 2, preferred = emptyList())).isEqualTo("not a magnet")
    }

    @Test
    fun `every magnet in a catalogue is trimmed, films, episodes and packs alike`() {
        val show = TestCatalogues.show().let { show ->
            show.copy(
                seasons = show.seasons.map { season ->
                    season.copy(
                        packs = listOf(CatalogMagnetDto(magnet = magnet)),
                        episodes = season.episodes.map { it.copy(magnets = listOf(CatalogMagnetDto(magnet = magnet))) },
                    )
                },
            )
        }
        val film = CatalogEntryDto(title = "F", magnets = listOf(CatalogMagnetDto(magnet = magnet)))

        val stripped = MagnetTrackers.stripAll(listOf(film, show), keep = 1, preferred = emptyList())

        val all = stripped[0].magnets + stripped[1].seasons.flatMap { s -> s.packs + s.episodes.flatMap { it.magnets } }
        assertThat(all.map { m -> m.magnet.split('&').count { it.startsWith("tr=") } }.toSet()).containsExactly(1)
    }
}
