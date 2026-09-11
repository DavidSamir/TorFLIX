package com.torfilx.core.data.catalog

import com.torfilx.core.catalogue.release.CatalogueFetcher
import com.torfilx.core.catalogue.release.CatalogueRejectReason
import com.torfilx.core.catalogue.release.CatalogueReleaseVerifier
import com.torfilx.core.catalogue.transport.CatalogueTransport
import com.torfilx.core.catalogue.transport.CatalogueTransportException
import com.torfilx.core.common.di.ApplicationScope
import com.torfilx.core.common.di.Dispatcher
import com.torfilx.core.common.di.TorfilxDispatcher
import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.core.common.time.TimeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CatalogUpdate"

/** What the catalogue updater is doing, for the Settings screen. */
sealed interface CatalogUpdateState {
    data object Idle : CatalogUpdateState

    data class Checking(val sinceMs: Long) : CatalogUpdateState

    data class Downloading(val version: Long, val progress: Float) : CatalogUpdateState

    data class UpToDate(val version: Long, val checkedAtMs: Long) : CatalogUpdateState

    data class Updated(val version: Long, val titleCount: Int, val atMs: Long) : CatalogUpdateState

    data class Failed(val reason: CatalogUpdateFailure, val detail: String?, val atMs: Long) : CatalogUpdateState
}

/** Why a check did not end with the newest catalogue installed, in words a viewer can act on. */
enum class CatalogUpdateFailure(val message: String) {
    NOT_CONFIGURED("This build has no catalogue publisher key, so it cannot update its catalogue"),
    NEEDS_SHARING("Turn on sharing to update the catalogue"),
    NEEDS_DHT("Turn on \"Find peers with DHT\" to update the catalogue"),
    NO_SESSION("The peer network is not running yet"),
    DHT_UNAVAILABLE("Could not reach the peer network"),
    NOT_FOUND("No catalogue has been published to the peer network yet"),
    NO_PEERS("Nobody is sharing the new catalogue right now"),
    REJECTED("A new catalogue failed verification and was not installed"),
    NEEDS_NEWER_APP("A newer catalogue needs a newer version of the app"),
    ENGINE("The peer network reported an error"),
}

/**
 * Keeps the catalogue current from the peer network, without a server.
 *
 * It never brings the peer network up on its own schedule. It waits for the session the torrent engine
 * runs once the viewer has consented to sharing, so nothing reaches the network before that consent,
 * and it stops the moment the session stops. Only an explicit "Check now" may ask for a session, and
 * that request is refused without consent too.
 *
 * While a session runs and updates are on, it seeds the installed release (so other viewers can get
 * it), checks 20 seconds after the session comes up, and then every six hours. A check finds the
 * signed pointer in the DHT, downloads and verifies a newer release, and swaps it in while the app runs.
 */
@Singleton
class CatalogUpdater @Inject constructor(
    private val transport: CatalogueTransport,
    private val catalog: LayeredCatalog,
    private val store: FetchedCatalogStore,
    verifier: CatalogueReleaseVerifier,
    private val keys: CataloguePublisherKeys,
    private val prefs: CatalogueUpdatePrefs,
    private val sessionGate: CatalogueSessionGate,
    private val appVersion: AppVersionProvider,
    private val timeProvider: TimeProvider,
    @ApplicationScope private val scope: CoroutineScope,
    @Dispatcher(TorfilxDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    private val fetcher = CatalogueFetcher(transport, verifier, keys.publicKeys)
    private val checkMutex = Mutex()
    private val started = AtomicBoolean(false)

    private val _state = MutableStateFlow<CatalogUpdateState>(CatalogUpdateState.Idle)
    val state: StateFlow<CatalogUpdateState> = _state.asStateFlow()

    @Volatile
    private var consecutiveFailures = 0

    /** False for a build that trusts no publisher key: it can never update its catalogue. */
    val isConfigured: Boolean get() = keys.isConfigured

    /** The release number of the catalogue inside the APK. Reads an asset, so it runs off the main thread. */
    suspend fun bundledCatalogueVersion(): Long = withContext(ioDispatcher) { catalog.bundledVersion() }

    /** What a check amounted to, which decides when the next one runs. */
    internal enum class CheckResult { SUCCESS, NOT_PUBLISHED, REFUSED, RETRY, BLOCKED, BUSY }

    /** Starts following the session. Call once, off the main thread; later calls do nothing. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(ioDispatcher) {
            runCatching {
                catalog.snapshot()
                val removed = store.deleteAllExceptInstalled()
                if (removed > 0) TorfilxLog.i(TAG, "Removed $removed leftover catalogue download(s)")
            }.onFailure { TorfilxLog.w(TAG, "Could not tidy leftover catalogue downloads", it) }

            combine(prefs.catalogUpdatesEnabled, transport.sessionRunning) { enabled, running -> enabled to running }
                .distinctUntilChanged()
                .collectLatest { (enabled, running) -> followSession(enabled, running) }
        }
    }

    private suspend fun followSession(enabled: Boolean, running: Boolean) {
        if (!running) return
        if (!enabled) {
            stopSeedingInstalled()
            _state.value = CatalogUpdateState.Idle
            return
        }
        reseedInstalled()
        delay(firstCheckDelayMs())
        while (true) {
            val result = runCheck(manual = false)
            delay(nextCheckDelayMs(result))
        }
    }

    /**
     * Runs a check now, on behalf of the viewer.
     *
     * @return false when a check is already running, in which case nothing new is started.
     */
    fun checkNow(): Boolean {
        if (checkMutex.isLocked) return false
        scope.launch(ioDispatcher) { runCheck(manual = true) }
        return true
    }

    /**
     * Removes the downloaded catalogue and goes back to the bundled one.
     *
     * The removed release is also remembered as refused, or the next check would simply install it
     * again. A newer release still installs.
     */
    suspend fun resetToBundled() = withContext(ioDispatcher) {
        checkMutex.withLock {
            val installed = store.installed()
            installed?.let { runCatching { transport.stopSeeding(it.infoHash) } }
            catalog.resetToBundled()
            store.deleteAllExceptInstalled()
            installed?.let { prefs.recordRejectedCatalogue(it.version, appVersion.versionCode) }
            _state.value = CatalogUpdateState.Idle
            TorfilxLog.i(
                TAG,
                "Back on the bundled catalogue ${catalog.info.value.version}" +
                    installed?.let { "; catalogue ${it.version} will not be downloaded again" }.orEmpty(),
            )
        }
    }

    // --- One check -------------------------------------------------------------------------------

    internal suspend fun runCheck(manual: Boolean): CheckResult {
        if (!checkMutex.tryLock()) return CheckResult.BUSY
        return try {
            check(manual)
        } finally {
            checkMutex.unlock()
        }
    }

    private suspend fun check(manual: Boolean): CheckResult {
        if (!keys.isConfigured) return blocked(CatalogUpdateFailure.NOT_CONFIGURED)
        val settings = prefs.catalogueUpdateSettings()
        if (!settings.sharingConsent) return blocked(CatalogUpdateFailure.NEEDS_SHARING)
        if (!settings.useDht) return blocked(CatalogUpdateFailure.NEEDS_DHT)
        if (!transport.sessionRunning.value && !(manual && sessionGate.ensureRunning())) {
            return blocked(CatalogUpdateFailure.NO_SESSION)
        }

        catalog.snapshot()
        val installed = catalog.info.value.version
        _state.value = CatalogUpdateState.Checking(timeProvider.nowMs())
        TorfilxLog.i(
            TAG,
            "Checking for a catalogue newer than $installed (${if (manual) "requested" else "scheduled"}, " +
                "${transport.dhtNodes()} DHT nodes)",
        )

        val rejected = prefs.rejectedCatalogue()?.takeIf { it.appVersionCode == appVersion.versionCode }
        val outcome = try {
            fetcher.fetch(
                CatalogueFetcher.Request(
                    installedVersion = installed,
                    rejectedVersion = rejected?.version,
                    appVersionCode = appVersion.versionCode,
                    saveDirFor = store::prepareDownload,
                    onDownloading = { version, progress ->
                        _state.value = CatalogUpdateState.Downloading(version, progress)
                    },
                ),
            )
        } catch (cancelled: CancellationException) {
            // The session went away mid-check. The transport has already dropped any partial download.
            withContext(NonCancellable) { runCatching { store.deleteAllExceptInstalled() } }
            throw cancelled
        }

        return when (outcome) {
            is CatalogueFetcher.Outcome.UpToDate -> succeed(
                CatalogUpdateState.UpToDate(installed, timeProvider.nowMs()),
                "up_to_date:${outcome.remoteVersion}",
            )
            is CatalogueFetcher.Outcome.Fetched -> install(outcome)
            CatalogueFetcher.Outcome.NotFound -> failed(CatalogUpdateFailure.NOT_FOUND, null, CheckResult.NOT_PUBLISHED)
            is CatalogueFetcher.Outcome.Unavailable ->
                failed(CatalogUpdateFailure.DHT_UNAVAILABLE, outcome.reason, CheckResult.RETRY)
            is CatalogueFetcher.Outcome.Skipped -> failed(
                CatalogUpdateFailure.REJECTED,
                "catalogue ${outcome.remoteVersion} was refused earlier and is not downloaded again",
                CheckResult.REFUSED,
            )
            is CatalogueFetcher.Outcome.Rejected -> refuse(outcome.version, outcome.infoHash, outcome.reason, outcome.detail)
            is CatalogueFetcher.Outcome.TransportFailed -> transportFailed(outcome)
        }
    }

    private suspend fun install(fetched: CatalogueFetcher.Outcome.Fetched): CheckResult {
        val version = fetched.manifest.catalogVersion
        val infoHash = fetched.download.infoHash
        val prepared = try {
            catalog.prepare(fetched.manifest, fetched.entries)
        } catch (incomplete: IncompleteCatalogueException) {
            return refuse(version, infoHash, CatalogueRejectReason.INCOMPLETE, incomplete.message.orEmpty())
        }

        return try {
            val previous = store.installed()
            val now = timeProvider.nowMs()
            store.install(version, infoHash, fetched.download.torrentBytes, now)
            catalog.commit(prepared, installedAtMs = now)
            // The new release keeps seeding from where it was downloaded; the old one is retired.
            if (previous != null && previous.version != version) {
                runCatching { transport.stopSeeding(previous.infoHash) }
                store.deleteRelease(previous.version)
            }
            store.deleteAllExceptInstalled()
            TorfilxLog.i(TAG, "Installed catalogue $version: ${prepared.titleCount} titles (was ${previous?.version ?: "bundled"})")
            succeed(CatalogUpdateState.Updated(version, prepared.titleCount, now), "updated:$version")
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            TorfilxLog.e(TAG, "Could not install catalogue $version", error)
            runCatching { transport.stopSeeding(infoHash) }
            store.deleteRelease(version)
            failed(CatalogUpdateFailure.ENGINE, "could not install catalogue $version: ${error.message}", CheckResult.RETRY)
        }
    }

    private suspend fun refuse(
        version: Long,
        infoHash: String,
        reason: CatalogueRejectReason,
        detail: String,
    ): CheckResult {
        runCatching { transport.stopSeeding(infoHash) }
        store.deleteRelease(version)
        prefs.recordRejectedCatalogue(version, appVersion.versionCode)
        TorfilxLog.e(TAG, "REJECTED catalogue $version ($reason): $detail")
        val failure = if (reason == CatalogueRejectReason.NEEDS_NEWER_APP) {
            CatalogUpdateFailure.NEEDS_NEWER_APP
        } else {
            CatalogUpdateFailure.REJECTED
        }
        return failed(failure, "catalogue $version: $reason", CheckResult.REFUSED)
    }

    private suspend fun transportFailed(outcome: CatalogueFetcher.Outcome.TransportFailed): CheckResult {
        val version = outcome.version
        val infoHash = outcome.infoHash
        return when (val error = outcome.error) {
            // The pointer names something that is not a catalogue release at all.
            is CatalogueTransportException.TooLarge, is CatalogueTransportException.UnexpectedContents ->
                if (version != null && infoHash != null) {
                    refuse(version, infoHash, CatalogueRejectReason.INVALID_ENTRIES, error.message.orEmpty())
                } else {
                    failed(CatalogUpdateFailure.REJECTED, error.message, CheckResult.REFUSED)
                }
            else -> {
                infoHash?.let { runCatching { transport.stopSeeding(it) } }
                version?.let { store.deleteRelease(it) }
                val failure = when (error) {
                    is CatalogueTransportException.NoSession -> CatalogUpdateFailure.NO_SESSION
                    is CatalogueTransportException.MetadataTimeout,
                    is CatalogueTransportException.DownloadTimeout -> CatalogUpdateFailure.NO_PEERS
                    else -> CatalogUpdateFailure.ENGINE
                }
                failed(failure, error.message, CheckResult.RETRY)
            }
        }
    }

    private suspend fun succeed(state: CatalogUpdateState, result: String): CheckResult {
        consecutiveFailures = 0
        _state.value = state
        prefs.recordCatalogueCheck(timeProvider.nowMs(), result, successful = true)
        TorfilxLog.i(TAG, "Catalogue check: $result")
        return CheckResult.SUCCESS
    }

    private suspend fun failed(reason: CatalogUpdateFailure, detail: String?, result: CheckResult): CheckResult {
        val now = timeProvider.nowMs()
        consecutiveFailures = if (result == CheckResult.RETRY) consecutiveFailures + 1 else 0
        _state.value = CatalogUpdateState.Failed(reason, detail, now)
        prefs.recordCatalogueCheck(now, "failed:${reason.name}", successful = false)
        TorfilxLog.w(TAG, "Catalogue check: ${reason.name}${detail?.let { " ($it)" }.orEmpty()}")
        return result
    }

    /** A check that could not reach the network at all. Not recorded as a check. */
    private fun blocked(reason: CatalogUpdateFailure): CheckResult {
        _state.value = CatalogUpdateState.Failed(reason, null, timeProvider.nowMs())
        TorfilxLog.i(TAG, "Catalogue check skipped: ${reason.name}")
        return CheckResult.BLOCKED
    }

    // --- Seeding the installed release -----------------------------------------------------------

    private suspend fun reseedInstalled() {
        runCatching {
            catalog.snapshot()
            val installed = store.installed() ?: return
            val inUse = catalog.info.value
            if (inUse.origin != CatalogueOrigin.FETCHED || inUse.version != installed.version) return
            val torrent = store.torrentBytes(installed.version) ?: return
            transport.seed(torrent, store.saveDirFor(installed.version))
        }.onFailure { error ->
            if (error is CancellationException) throw error
            TorfilxLog.w(TAG, "Could not seed the installed catalogue", error)
        }
    }

    private suspend fun stopSeedingInstalled() {
        val installed = store.installed() ?: return
        runCatching { transport.stopSeeding(installed.infoHash) }
    }

    // --- Scheduling ------------------------------------------------------------------------------

    private suspend fun firstCheckDelayMs(): Long {
        val lastSuccess = prefs.catalogueUpdateRecord.first().lastSuccessMs ?: return INITIAL_DELAY_MS
        val sinceLast = timeProvider.nowMs() - lastSuccess
        return if (sinceLast in 0 until MIN_RECHECK_MS) {
            (MIN_RECHECK_MS - sinceLast).coerceAtLeast(INITIAL_DELAY_MS)
        } else {
            INITIAL_DELAY_MS
        }
    }

    internal fun nextCheckDelayMs(result: CheckResult): Long = when (result) {
        CheckResult.SUCCESS, CheckResult.REFUSED -> CHECK_INTERVAL_MS
        CheckResult.NOT_PUBLISHED, CheckResult.BLOCKED -> HOURLY_MS
        CheckResult.BUSY -> BUSY_RETRY_MS
        CheckResult.RETRY -> {
            val doublings = (consecutiveFailures - 1).coerceIn(0, MAX_DOUBLINGS)
            (RETRY_BASE_MS shl doublings).coerceAtMost(CHECK_INTERVAL_MS)
        }
    }

    internal companion object {
        /** Long enough for a DHT restored from saved state, or a cold one, to find its nodes. */
        const val INITIAL_DELAY_MS = 20_000L
        const val MIN_RECHECK_MS = 60 * 60_000L
        const val HOURLY_MS = 60 * 60_000L
        const val CHECK_INTERVAL_MS = 6 * 60 * 60_000L
        const val RETRY_BASE_MS = 15 * 60_000L
        const val BUSY_RETRY_MS = 60_000L
        private const val MAX_DOUBLINGS = 5
    }
}
