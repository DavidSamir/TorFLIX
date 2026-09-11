package com.torfilx.core.catalogue.release

import com.google.common.truth.Truth.assertThat
import com.torfilx.core.catalogue.format.CatalogEntryDto
import com.torfilx.core.catalogue.format.CatalogRelease
import com.torfilx.core.catalogue.testing.CatalogueTestKeys
import com.torfilx.core.catalogue.testing.FakeCatalogueTransport
import com.torfilx.core.catalogue.testing.TestCatalogues
import com.torfilx.core.catalogue.transport.CatalogueTransportException
import com.torfilx.core.catalogue.transport.PointerLookup
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The check sequence against an in-memory peer network: what is looked up, downloaded and accepted. */
class CatalogueFetcherTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val transport = FakeCatalogueTransport()
    private val progress = mutableListOf<Pair<Long, Float>>()

    private fun fetcher(keys: List<ByteArray> = listOf(CatalogueTestKeys.PUBLIC_KEY)) =
        CatalogueFetcher(transport, CatalogueTestKeys.verifier(), keys)

    private fun request(installed: Long = 0, rejected: Long? = null) = CatalogueFetcher.Request(
        installedVersion = installed,
        rejectedVersion = rejected,
        appVersionCode = APP_VERSION_CODE,
        saveDirFor = { version -> File(tmp.root, "incoming/$version") },
        onDownloading = { version, fraction -> progress += version to fraction },
    )

    /** A release sitting in the fake swarm, as a publisher would have written it. */
    private fun published(
        version: Long,
        seed: ByteArray = CatalogueTestKeys.SEED,
        entries: List<CatalogEntryDto> = TestCatalogues.entries(3),
    ): File = TestCatalogues.writeRelease(tmp.newFolder("swarm-$version-${seed.first()}"), version, entries, seed).releaseRoot

    @Test
    fun `nothing published is reported as not found and nothing is downloaded`() = runTest {
        assertThat(fetcher().fetch(request())).isEqualTo(CatalogueFetcher.Outcome.NotFound)
        assertThat(transport.downloads).isEmpty()
    }

    @Test
    fun `the trusted publisher keys are what gets looked up`() = runTest {
        fetcher().fetch(request())
        assertThat(transport.lookups.single()).containsExactly(CatalogueTestKeys.PUBLIC_KEY_HEX)
    }

    @Test
    fun `a lookup that cannot run says why`() = runTest {
        transport.lookupResult = PointerLookup.Unavailable("DHT disabled")
        assertThat(fetcher().fetch(request())).isEqualTo(CatalogueFetcher.Outcome.Unavailable("DHT disabled"))
    }

    @Test
    fun `without a publisher key nothing is looked up`() = runTest {
        assertThat(fetcher(keys = emptyList()).fetch(request())).isInstanceOf(CatalogueFetcher.Outcome.Unavailable::class.java)
        assertThat(transport.lookups).isEmpty()
    }

    @Test
    fun `a release that is not newer is left alone`() = runTest {
        transport.publish(published(7), version = 7)

        assertThat(fetcher().fetch(request(installed = 7))).isEqualTo(CatalogueFetcher.Outcome.UpToDate(7, 7))
        assertThat(fetcher().fetch(request(installed = 9))).isEqualTo(CatalogueFetcher.Outcome.UpToDate(7, 9))
        assertThat(transport.downloads).isEmpty()
    }

    @Test
    fun `a release already rejected is not downloaded again`() = runTest {
        transport.publish(published(8), version = 8)

        assertThat(fetcher().fetch(request(installed = 7, rejected = 8))).isEqualTo(CatalogueFetcher.Outcome.Skipped(8))
        assertThat(transport.downloads).isEmpty()
    }

    @Test
    fun `a newer release is downloaded into its own directory and verified`() = runTest {
        val entries = TestCatalogues.entries(4)
        val infoHash = transport.publish(published(8, entries = entries), version = 8)

        val outcome = fetcher().fetch(request(installed = 7))

        val fetched = outcome as? CatalogueFetcher.Outcome.Fetched ?: throw AssertionError("expected Fetched, was $outcome")
        assertThat(fetched.manifest.catalogVersion).isEqualTo(8)
        assertThat(fetched.entries).isEqualTo(entries)
        assertThat(fetched.download.infoHash).isEqualTo(infoHash)
        assertThat(fetched.pointer.pointer.infoHash).isEqualTo(infoHash)
        val download = transport.downloads.single()
        assertThat(download.saveDir).isEqualTo(File(tmp.root, "incoming/8"))
        assertThat(download.maxBytes).isEqualTo(CatalogRelease.MAX_TORRENT_BYTES)
        assertThat(fetched.download.rootDir).isEqualTo(File(tmp.root, "incoming/8/${CatalogRelease.rootDirName(8)}"))
        assertThat(progress.first()).isEqualTo(8L to 0f)
        assertThat(progress.last()).isEqualTo(8L to 1f)
    }

    @Test
    fun `a release signed by anyone else is rejected once downloaded`() = runTest {
        val infoHash = transport.publish(published(8, seed = CatalogueTestKeys.OTHER_SEED), version = 8)

        val outcome = fetcher().fetch(request(installed = 7))

        assertThat(outcome).isInstanceOf(CatalogueFetcher.Outcome.Rejected::class.java)
        outcome as CatalogueFetcher.Outcome.Rejected
        assertThat(outcome.reason).isEqualTo(CatalogueRejectReason.BAD_SIGNATURE)
        assertThat(outcome.version).isEqualTo(8)
        assertThat(outcome.infoHash).isEqualTo(infoHash)
    }

    @Test
    fun `a pointer whose version disagrees with the release it names is rejected`() = runTest {
        // A genuine release 7 re-announced as 8 must not be installed as 8.
        transport.publish(published(7), version = 8)

        val outcome = fetcher().fetch(request(installed = 7))

        assertThat((outcome as CatalogueFetcher.Outcome.Rejected).reason).isEqualTo(CatalogueRejectReason.POINTER_MISMATCH)
    }

    @Test
    fun `a pointer in an unknown format is refused without downloading`() = runTest {
        transport.publish(published(8), version = 8, format = 2)

        val outcome = fetcher().fetch(request(installed = 7))

        assertThat((outcome as CatalogueFetcher.Outcome.Rejected).reason).isEqualTo(CatalogueRejectReason.UNSUPPORTED_POINTER)
        assertThat(transport.downloads).isEmpty()
    }

    @Test
    fun `a failed download is reported with the release it was fetching`() = runTest {
        val infoHash = transport.publish(published(8), version = 8)
        val error = CatalogueTransportException.DownloadTimeout(progress = 0.5f)
        transport.downloadError = error

        val outcome = fetcher().fetch(request(installed = 7))

        assertThat(outcome).isEqualTo(CatalogueFetcher.Outcome.TransportFailed(8, infoHash, error))
    }

    @Test
    fun `a failed lookup is reported as a transport failure without a release`() = runTest {
        val error = CatalogueTransportException.Engine("socket closed")
        transport.lookupError = error

        assertThat(fetcher().fetch(request())).isEqualTo(CatalogueFetcher.Outcome.TransportFailed(null, null, error))
    }

    private companion object {
        const val APP_VERSION_CODE = 16
    }
}
