package com.torfilx.core.data.catalog

import com.google.common.truth.Truth.assertThat
import com.torfilx.core.catalogue.format.CatalogEntryDto
import com.torfilx.core.catalogue.testing.CatalogueTestKeys
import com.torfilx.core.catalogue.testing.FakeCatalogueTransport
import com.torfilx.core.catalogue.testing.TestCatalogues
import com.torfilx.core.catalogue.transport.CatalogueTransportException
import com.torfilx.core.data.catalog.CatalogUpdater.CheckResult
import com.torfilx.core.testing.FakeTimeProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The updater against an in-memory peer network and a real catalogue store on disk.
 *
 * Covers what must never go wrong on a television: nothing reaches the network without consent, a bad
 * release never replaces a good catalogue, a refused release is not fetched over and over, and checks
 * follow the session rather than running on their own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogUpdaterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val bundledEntries = TestCatalogues.entries(3, titlePrefix = "Bundled")
    private val transport = FakeCatalogueTransport()
    private val prefs = FakeCatalogueUpdatePrefs()
    private val time = FakeTimeProvider()
    private lateinit var store: FetchedCatalogStore
    private lateinit var catalog: LayeredCatalog
    private lateinit var gate: FakeSessionGate

    @Before
    fun setUp() {
        store = FetchedCatalogStore { File(tmp.root, "catalogue") }
        catalog = layeredCatalog(FakeCatalogAssetSource.bundled(5, bundledEntries), store)
        gate = FakeSessionGate(transport)
    }

    private fun TestScope.updater(
        keys: List<ByteArray> = listOf(CatalogueTestKeys.PUBLIC_KEY),
        appVersionCode: Int = FixedAppVersion.APP_VERSION_CODE,
    ) = CatalogUpdater(
        transport = transport,
        catalog = catalog,
        store = store,
        verifier = CatalogueTestKeys.verifier(),
        keys = CataloguePublisherKeys(keys),
        prefs = prefs,
        sessionGate = gate,
        appVersion = FixedAppVersion(appVersionCode),
        timeProvider = time,
        scope = backgroundScope,
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    /** Puts release [version] in the fake swarm, as the publisher would. Returns its info hash. */
    private fun publish(
        version: Long,
        entries: List<CatalogEntryDto> = TestCatalogues.entries(4, titlePrefix = "Release $version"),
        seed: ByteArray = CatalogueTestKeys.SEED,
        minVersionCode: Int? = null,
    ): String {
        val root = TestCatalogues.writeRelease(tmp.newFolder(), version, entries, seed, minVersionCode).releaseRoot
        return transport.publish(root, version)
    }

    private fun CatalogUpdater.failure(): CatalogUpdateFailure? = (state.value as? CatalogUpdateState.Failed)?.reason

    // --- What a check does -----------------------------------------------------------------------

    @Test
    fun `with nothing published a check says so and changes nothing`() = runTest {
        val updater = updater()

        assertThat(updater.runCheck(manual = false)).isEqualTo(CheckResult.NOT_PUBLISHED)

        assertThat(updater.failure()).isEqualTo(CatalogUpdateFailure.NOT_FOUND)
        assertThat(catalog.info.value.origin).isEqualTo(CatalogueOrigin.BUNDLED)
        assertThat(transport.downloads).isEmpty()
        assertThat(prefs.recordFlow.value.lastResult).isEqualTo("failed:NOT_FOUND")
    }

    @Test
    fun `a newer release is downloaded, verified, installed and swapped in`() = runTest {
        val entries = TestCatalogues.entries(4, titlePrefix = "Release 7")
        val infoHash = publish(7, entries)
        val updater = updater()
        val generationBefore = catalog.snapshot().info.generation

        assertThat(updater.runCheck(manual = false)).isEqualTo(CheckResult.SUCCESS)

        assertThat(updater.state.value).isEqualTo(CatalogUpdateState.Updated(7, 4, time.nowMs()))
        val info = catalog.info.value
        assertThat(info.origin).isEqualTo(CatalogueOrigin.FETCHED)
        assertThat(info.version).isEqualTo(7)
        assertThat(info.generation).isEqualTo(generationBefore + 1)
        assertThat(catalog.item(entries.first().id!!)).isNotNull()
        assertThat(store.installed()?.version).isEqualTo(7)
        assertThat(store.installed()?.infoHash).isEqualTo(infoHash)
        assertThat(store.torrentBytes(7)).isEqualTo(FakeCatalogueTransport.torrentBytesFor(infoHash))
        assertThat(transport.downloads.single().saveDir).isEqualTo(store.saveDirFor(7))
        assertThat(prefs.recordFlow.value.lastResult).isEqualTo("updated:7")
        assertThat(prefs.recordFlow.value.lastSuccessMs).isEqualTo(time.nowMs())
    }

    @Test
    fun `the next release retires the one before it`() = runTest {
        val hash7 = publish(7)
        val updater = updater()
        updater.runCheck(manual = false)

        publish(8)
        assertThat(updater.runCheck(manual = false)).isEqualTo(CheckResult.SUCCESS)

        assertThat(store.installed()?.version).isEqualTo(8)
        assertThat(catalog.info.value.version).isEqualTo(8)
        assertThat(transport.stoppedSeeding).contains(hash7)
        assertThat(store.saveDirFor(7).exists()).isFalse()
    }

    @Test
    fun `a release that is not newer downloads nothing`() = runTest {
        publish(5)
        val updater = updater()

        assertThat(updater.runCheck(manual = false)).isEqualTo(CheckResult.SUCCESS)

        assertThat(updater.state.value).isEqualTo(CatalogUpdateState.UpToDate(5, time.nowMs()))
        assertThat(transport.downloads).isEmpty()
        assertThat(prefs.recordFlow.value.lastResult).isEqualTo("up_to_date:5")
    }

    @Test
    fun `a forged release is refused, cleaned up, and not downloaded again`() = runTest {
        val infoHash = publish(7, seed = CatalogueTestKeys.OTHER_SEED)
        val updater = updater()

        assertThat(updater.runCheck(manual = false)).isEqualTo(CheckResult.REFUSED)

        assertThat(updater.failure()).isEqualTo(CatalogUpdateFailure.REJECTED)
        assertThat(prefs.rejected).isEqualTo(RejectedCatalogue(7, FixedAppVersion.APP_VERSION_CODE))
        assertThat(transport.stoppedSeeding).contains(infoHash)
        assertThat(store.saveDirFor(7).exists()).isFalse()
        assertThat(catalog.info.value.origin).isEqualTo(CatalogueOrigin.BUNDLED)

        assertThat(updater.runCheck(manual = false)).isEqualTo(CheckResult.REFUSED)
        assertThat(transport.downloads).hasSize(1)
    }

    @Test
    fun `a release refused by one app build is tried again by another`() = runTest {
        publish(7)
        prefs.rejected = RejectedCatalogue(7, appVersionCode = 15)

        assertThat(updater(appVersionCode = 16).runCheck(manual = false)).isEqualTo(CheckResult.SUCCESS)
    }

    @Test
    fun `a release made for a newer app says so and changes nothing`() = runTest {
        publish(7, minVersionCode = 99)
        val updater = updater()

        assertThat(updater.runCheck(manual = false)).isEqualTo(CheckResult.REFUSED)

        assertThat(updater.failure()).isEqualTo(CatalogUpdateFailure.NEEDS_NEWER_APP)
        assertThat(catalog.info.value.origin).isEqualTo(CatalogueOrigin.BUNDLED)
    }

    @Test
    fun `a pointer to something that is not a catalogue is refused and remembered`() = runTest {
        publish(7)
        transport.downloadError = CatalogueTransportException.UnexpectedContents("the torrent holds a film")
        val updater = updater()

        assertThat(updater.runCheck(manual = false)).isEqualTo(CheckResult.REFUSED)

        assertThat(prefs.rejected?.version).isEqualTo(7)
        assertThat(store.saveDirFor(7).exists()).isFalse()
    }

    @Test
    fun `when nobody shares the release the check says so, cleans up and backs off`() = runTest {
        publish(7)
        transport.downloadError = CatalogueTransportException.MetadataTimeout("no peers")
        val updater = updater()

        assertThat(updater.runCheck(manual = false)).isEqualTo(CheckResult.RETRY)
        assertThat(updater.failure()).isEqualTo(CatalogUpdateFailure.NO_PEERS)
        assertThat(store.saveDirFor(7).exists()).isFalse()
        assertThat(updater.nextCheckDelayMs(CheckResult.RETRY)).isEqualTo(CatalogUpdater.RETRY_BASE_MS)

        updater.runCheck(manual = false)
        assertThat(updater.nextCheckDelayMs(CheckResult.RETRY)).isEqualTo(CatalogUpdater.RETRY_BASE_MS * 2)

        transport.downloadError = null
        assertThat(updater.runCheck(manual = false)).isEqualTo(CheckResult.SUCCESS)
        assertThat(updater.nextCheckDelayMs(CheckResult.SUCCESS)).isEqualTo(CatalogUpdater.CHECK_INTERVAL_MS)
        assertThat(updater.nextCheckDelayMs(CheckResult.NOT_PUBLISHED)).isEqualTo(CatalogUpdater.HOURLY_MS)
        assertThat(updater.nextCheckDelayMs(CheckResult.BLOCKED)).isEqualTo(CatalogUpdater.HOURLY_MS)
    }

    // --- What must stop a check before it touches the network ------------------------------------

    @Test
    fun `without sharing consent nothing touches the network, even on request`() = runTest {
        publish(7)
        prefs.sharingConsent = false
        val updater = updater()

        assertThat(updater.runCheck(manual = true)).isEqualTo(CheckResult.BLOCKED)

        assertThat(updater.failure()).isEqualTo(CatalogUpdateFailure.NEEDS_SHARING)
        assertThat(transport.lookups).isEmpty()
        assertThat(gate.calls).isEqualTo(0)
        assertThat(prefs.recordFlow.value.lastCheckMs).isNull()
    }

    @Test
    fun `with the DHT turned off nothing touches the network`() = runTest {
        publish(7)
        prefs.useDht = false
        val updater = updater()

        assertThat(updater.runCheck(manual = true)).isEqualTo(CheckResult.BLOCKED)

        assertThat(updater.failure()).isEqualTo(CatalogUpdateFailure.NEEDS_DHT)
        assertThat(transport.lookups).isEmpty()
    }

    @Test
    fun `a build with no publisher key never checks`() = runTest {
        publish(7)
        val updater = updater(keys = emptyList())

        assertThat(updater.isConfigured).isFalse()
        assertThat(updater.runCheck(manual = true)).isEqualTo(CheckResult.BLOCKED)

        assertThat(updater.failure()).isEqualTo(CatalogUpdateFailure.NOT_CONFIGURED)
        assertThat(transport.lookups).isEmpty()
    }

    @Test
    fun `a scheduled check needs a running session, and a requested one may start it`() = runTest {
        publish(7)
        transport.running.value = false
        val updater = updater()

        assertThat(updater.runCheck(manual = false)).isEqualTo(CheckResult.BLOCKED)
        assertThat(updater.failure()).isEqualTo(CatalogUpdateFailure.NO_SESSION)
        assertThat(gate.calls).isEqualTo(0)
        assertThat(transport.lookups).isEmpty()

        assertThat(updater.runCheck(manual = true)).isEqualTo(CheckResult.SUCCESS)
        assertThat(gate.calls).isEqualTo(1)

        gate.allow = false
        transport.running.value = false
        assertThat(updater.runCheck(manual = true)).isEqualTo(CheckResult.BLOCKED)
    }

    // --- Going back ------------------------------------------------------------------------------

    @Test
    fun `going back to the built-in catalogue keeps that release from returning, but not a newer one`() = runTest {
        val infoHash = publish(7)
        val updater = updater()
        updater.runCheck(manual = false)

        updater.resetToBundled()

        assertThat(catalog.info.value.origin).isEqualTo(CatalogueOrigin.BUNDLED)
        assertThat(catalog.info.value.version).isEqualTo(5)
        assertThat(store.installed()).isNull()
        assertThat(transport.stoppedSeeding).contains(infoHash)
        assertThat(prefs.rejected).isEqualTo(RejectedCatalogue(7, FixedAppVersion.APP_VERSION_CODE))
        assertThat(updater.state.value).isEqualTo(CatalogUpdateState.Idle)

        assertThat(updater.runCheck(manual = false)).isEqualTo(CheckResult.REFUSED)
        assertThat(transport.downloads).hasSize(1)

        publish(8)
        assertThat(updater.runCheck(manual = false)).isEqualTo(CheckResult.SUCCESS)
        assertThat(catalog.info.value.version).isEqualTo(8)
    }

    // --- Following the session -------------------------------------------------------------------

    @Test
    fun `once a session runs the installed release is seeded and checks follow the schedule`() = runTest {
        val installed = store.installTestRelease(7, TestCatalogues.entries(4, titlePrefix = "Earlier"))
        publish(7)
        transport.running.value = false
        val updater = updater()

        updater.start()
        runCurrent()
        assertThat(transport.seeded).isEmpty()
        assertThat(transport.lookups).isEmpty()

        transport.running.value = true
        runCurrent()
        assertThat(transport.seeded.single()).isEqualTo(installed.infoHash to store.saveDirFor(7))
        assertThat(transport.lookups).isEmpty()

        advanceTimeBy(CatalogUpdater.INITIAL_DELAY_MS + 1)
        assertThat(transport.lookups).hasSize(1)

        advanceTimeBy(CatalogUpdater.CHECK_INTERVAL_MS)
        assertThat(transport.lookups).hasSize(2)

        // The session stops: nothing more happens, however long it is.
        transport.running.value = false
        runCurrent()
        advanceTimeBy(CatalogUpdater.CHECK_INTERVAL_MS * 3)
        assertThat(transport.lookups).hasSize(2)
    }

    @Test
    fun `turning updates off stops sharing the installed release and stops checking`() = runTest {
        val installed = store.installTestRelease(7, TestCatalogues.entries(4, titlePrefix = "Earlier"))
        publish(7)
        val updater = updater()
        updater.start()
        runCurrent()
        assertThat(transport.seeded).hasSize(1)

        prefs.enabledFlow.value = false
        runCurrent()

        assertThat(transport.stoppedSeeding).containsExactly(installed.infoHash)
        advanceTimeBy(CatalogUpdater.CHECK_INTERVAL_MS)
        assertThat(transport.lookups).isEmpty()
        assertThat(updater.state.value).isEqualTo(CatalogUpdateState.Idle)
    }

    @Test
    fun `a recent successful check postpones the first check of a new session`() = runTest {
        publish(5)
        prefs.recordFlow.value = CatalogueUpdateRecord(
            lastCheckMs = time.nowMs() - 10 * 60_000L,
            lastResult = "up_to_date:5",
            lastSuccessMs = time.nowMs() - 10 * 60_000L,
        )
        val updater = updater()

        updater.start()
        advanceTimeBy(CatalogUpdater.INITIAL_DELAY_MS + 1)
        assertThat(transport.lookups).isEmpty()

        advanceTimeBy(50 * 60_000L)
        assertThat(transport.lookups).hasSize(1)
    }
}
