package com.torfilx.feature.settings

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.core.data.catalog.CatalogUpdateState
import com.torfilx.core.data.catalog.CatalogUpdater
import com.torfilx.core.data.catalog.CatalogueInfo
import com.torfilx.core.data.catalog.CatalogueUpdateRecord
import com.torfilx.core.data.repository.ContributionRepository
import com.torfilx.core.data.repository.MediaRepository
import com.torfilx.core.data.repository.ProgressRepository
import com.torfilx.core.data.settings.SettingsRepository
import com.torfilx.core.data.torrent.TorrentCoordinator
import com.torfilx.core.model.AppSettings
import com.torfilx.core.model.AspectPreference
import com.torfilx.core.model.AutoplayCountdown
import com.torfilx.core.model.LibrarySort
import com.torfilx.core.model.MetadataTimeout
import com.torfilx.core.model.QualityPreference
import com.torfilx.core.model.SeekStep
import com.torfilx.core.model.StreamedTotals
import com.torfilx.core.model.StreamingMode
import com.torfilx.core.model.SubtitleSize
import com.torfilx.core.model.SubtitleStyle
import com.torfilx.core.model.UploadLimit
import com.torfilx.core.torrent.SharingStats
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

private const val TAG = "SettingsVM"

/** Which section a status line belongs under, so it appears next to the button that caused it. */
enum class MessageSection { SHARING, CATALOGUE, LIBRARY, WATCH_DATA, ABOUT }

data class SettingsMessage(val text: String, val section: MessageSection)

/** What the About section shows: enough to tell which build is on which TV from a photo of the screen. */
data class AboutInfo(
    val appVersion: String = "",
    val device: String = "",
    val system: String = "",
)

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val sharingConsent: Boolean = false,
    val seedingEnabled: Boolean = true,
    val storageFraction: Float = 0.5f,
    val sharingStats: SharingStats = SharingStats(),
    /** Streamed in and out: today, the last 30 days, and since the record was last cleared. */
    val streamed: StreamedTotals = StreamedTotals(),
    val torrentAvailable: Boolean = false,
    val catalogue: CatalogueSettingsState = CatalogueSettingsState(),
    val about: AboutInfo = AboutInfo(),
    val message: SettingsMessage? = null,
)

/** Everything the Catalogue section shows. */
data class CatalogueSettingsState(
    val inUse: CatalogueInfo = CatalogueInfo(),
    val bundledVersion: Long = 0,
    val updatesEnabled: Boolean = true,
    val update: CatalogUpdateState = CatalogUpdateState.Idle,
    val record: CatalogueUpdateRecord = CatalogueUpdateRecord(),
    /** False for a build that trusts no publisher key, which can never update. */
    val publisherConfigured: Boolean = true,
)

/**
 * Settings for a server-less app: playback preferences, language, sharing, and the catalogue.
 *
 * There is no server address, token or connection test. The catalogue ships with the app and is kept
 * current from the peer network; playback happens over BitTorrent.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val mediaRepository: MediaRepository,
    private val progressRepository: ProgressRepository,
    private val contributionRepository: ContributionRepository,
    private val torrentCoordinator: TorrentCoordinator,
    private val catalogUpdater: CatalogUpdater,
    private val crashStore: com.torfilx.core.common.log.CrashStore,
    private val userDataBackup: com.torfilx.core.data.backup.UserDataBackup,
) : ViewModel() {

    private val message = MutableStateFlow<SettingsMessage?>(null)
    private val bundledVersion = MutableStateFlow(0L)
    private val about = readAboutInfo()

    private val catalogue: Flow<CatalogueSettingsState> = combine(
        mediaRepository.observeCatalogue(),
        settingsRepository.catalogUpdatesEnabled,
        catalogUpdater.state,
        settingsRepository.catalogueUpdateRecord,
        bundledVersion,
    ) { inUse, enabled, update, record, bundled ->
        CatalogueSettingsState(
            inUse = inUse,
            bundledVersion = bundled,
            updatesEnabled = enabled,
            update = update,
            record = record,
            publisherConfigured = catalogUpdater.isConfigured,
        )
    }

    private val sharing: Flow<SharingSnapshot> = combine(
        settingsRepository.sharingConsent,
        settingsRepository.seedingEnabled,
        settingsRepository.storageFraction,
        torrentCoordinator.stats,
        contributionRepository.streamedTotals(System.currentTimeMillis()),
    ) { consent, seeding, fraction, stats, streamed -> SharingSnapshot(consent, seeding, fraction, stats, streamed) }

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        sharing,
        catalogue,
        message,
    ) { settings, share, catalogueState, msg ->
        SettingsUiState(
            settings = settings,
            sharingConsent = share.consent,
            seedingEnabled = share.seeding,
            storageFraction = share.fraction,
            sharingStats = share.stats,
            streamed = share.streamed,
            torrentAvailable = torrentCoordinator.isAvailable(),
            catalogue = catalogueState,
            about = about,
            message = msg,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SettingsUiState(about = about))

    private data class SharingSnapshot(
        val consent: Boolean,
        val seeding: Boolean,
        val fraction: Float,
        val stats: SharingStats,
        val streamed: StreamedTotals,
    )

    init {
        viewModelScope.launch { bundledVersion.value = catalogUpdater.bundledCatalogueVersion() }
    }

    /** Turning sharing off stops uploading immediately and makes torrent playback unavailable. */
    fun setSharingConsent(consented: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSharingConsent(consented)
            say(
                MessageSection.SHARING,
                if (consented) {
                    "Sharing is on. You upload what you are watching, within the storage limit."
                } else {
                    "Sharing is off. Playback is unavailable until you turn it back on."
                },
            )
        }
    }

    fun setSeedingEnabled(enabled: Boolean) = launchSetting { settingsRepository.setSeedingEnabled(enabled) }

    /** The share of free disk the torrent cache may use; one of [STORAGE_CHOICES]. */
    fun setStorageFraction(fraction: Float) = launchSetting { settingsRepository.setStorageFraction(fraction) }

    /** Takes effect on the running session straight away. The choices never go below the built-in cap. */
    fun setUploadLimit(limit: UploadLimit) = launchSetting { settingsRepository.setUploadLimit(limit) }

    // --- Catalogue -------------------------------------------------------------------------------

    fun setCatalogUpdatesEnabled(enabled: Boolean) =
        launchSetting { settingsRepository.setCatalogUpdatesEnabled(enabled) }

    /** Looks for a newer catalogue now. Starts the peer network if sharing is on and it is not running. */
    fun checkCatalogueNow() {
        if (!catalogUpdater.checkNow()) say(MessageSection.CATALOGUE, "A catalogue check is already running.")
    }

    /** Drops the downloaded catalogue and goes back to the one inside the app. */
    fun useBundledCatalogue() {
        viewModelScope.launch {
            runCatching { catalogUpdater.resetToBundled() }
                .onSuccess {
                    say(
                        MessageSection.CATALOGUE,
                        "Back on the built-in catalogue. The catalogue you left will not be " +
                            "downloaded again; a newer one still will.",
                    )
                }
                .onFailure {
                    TorfilxLog.w(TAG, "Could not go back to the built-in catalogue", it)
                    say(MessageSection.CATALOGUE, "Could not switch catalogues: ${it.message}")
                }
        }
    }

    // --- Playback, language, library -------------------------------------------------------------

    fun setAudioLanguage(language: String?) = launchSetting { settingsRepository.setAudioLanguage(language) }
    fun setSubtitleLanguage(language: String?) = launchSetting { settingsRepository.setSubtitleLanguage(language) }
    fun setSubtitlesEnabled(enabled: Boolean) = launchSetting { settingsRepository.setSubtitlesEnabled(enabled) }
    fun setSubtitleSize(size: SubtitleSize) = launchSetting { settingsRepository.setSubtitleSize(size) }
    fun setSubtitleStyle(style: SubtitleStyle) = launchSetting { settingsRepository.setSubtitleStyle(style) }
    fun setAutoplayNext(enabled: Boolean) = launchSetting { settingsRepository.setAutoplayNext(enabled) }
    fun setAutoplayCountdown(value: AutoplayCountdown) =
        launchSetting { settingsRepository.setAutoplayCountdown(value) }
    fun setQuality(preference: QualityPreference) = launchSetting { settingsRepository.setQuality(preference) }
    fun setFrameRateMatching(enabled: Boolean) = launchSetting { settingsRepository.setFrameRateMatching(enabled) }
    fun setTunneledPlayback(enabled: Boolean) = launchSetting { settingsRepository.setTunneledPlayback(enabled) }
    fun setSkipIntroAutomatically(enabled: Boolean) =
        launchSetting { settingsRepository.setSkipIntroAutomatically(enabled) }
    fun setSeekStep(step: SeekStep) = launchSetting { settingsRepository.setSeekStep(step) }
    fun setDefaultAspect(value: AspectPreference) = launchSetting { settingsRepository.setDefaultAspect(value) }
    fun setShowStreamStats(enabled: Boolean) = launchSetting { settingsRepository.setShowStreamStats(enabled) }
    fun setLibrarySort(sort: LibrarySort) = launchSetting { settingsRepository.setLibrarySort(sort) }
    fun setHideWatched(enabled: Boolean) = launchSetting { settingsRepository.setHideWatched(enabled) }
    fun setReduceMotion(enabled: Boolean) = launchSetting { settingsRepository.setReduceMotion(enabled) }

    // --- Streaming engine ------------------------------------------------------------------------

    fun setUseDht(enabled: Boolean) = launchSetting { settingsRepository.setUseDht(enabled) }
    fun setUseExtraTrackers(enabled: Boolean) =
        launchSetting { settingsRepository.setUseExtraTrackers(enabled) }
    fun setMetadataTimeout(value: MetadataTimeout) =
        launchSetting { settingsRepository.setMetadataTimeout(value) }
    fun setStreamingMode(mode: StreamingMode) = launchSetting { settingsRepository.setStreamingMode(mode) }
    fun setForceSoftwareDecoder(enabled: Boolean) =
        launchSetting { settingsRepository.setForceSoftwareDecoder(enabled) }

    // --- Maintenance -----------------------------------------------------------------------------

    /**
     * Clears downloaded torrent data; watch progress and My List are kept.
     *
     * The record of what was shared goes with it (see [TorrentCoordinator.clearAllData]), so the
     * streamed totals start again from zero, and the message says so.
     */
    fun clearDownloadedData() {
        viewModelScope.launch {
            runCatching { torrentCoordinator.clearAllData() }
                .onSuccess {
                    say(
                        MessageSection.SHARING,
                        "Downloaded data cleared, and the streamed totals with it. Watch progress was kept.",
                    )
                }
                .onFailure { say(MessageSection.SHARING, "Could not clear downloaded data: ${it.message}") }
        }
    }

    fun clearSearchHistory() {
        viewModelScope.launch {
            mediaRepository.clearSearchHistory()
            say(MessageSection.LIBRARY, "Search history cleared.")
        }
    }

    /** Forgets resume points and watched marks, which empties Continue Watching. My List stays. */
    fun clearWatchHistory() {
        viewModelScope.launch {
            runCatching { progressRepository.clearWatchHistory() }
                .onSuccess {
                    say(MessageSection.WATCH_DATA, "Watch history cleared. Continue Watching is empty; My List was kept.")
                }
                .onFailure {
                    TorfilxLog.w(TAG, "Could not clear watch history", it)
                    say(MessageSection.WATCH_DATA, "Could not clear watch history: ${it.message}")
                }
        }
    }

    /** Puts every preference back to its default. The sharing decision is the viewer's and stays. */
    fun resetSettings() {
        viewModelScope.launch {
            runCatching { settingsRepository.resetToDefaults() }
                .onSuccess {
                    val sharing = if (uiState.value.sharingConsent) "on" else "off"
                    say(MessageSection.ABOUT, "Settings are back to their defaults. Sharing stayed $sharing.")
                }
                .onFailure {
                    TorfilxLog.w(TAG, "Could not reset settings", it)
                    say(MessageSection.ABOUT, "Could not reset settings: ${it.message}")
                }
        }
    }

    /**
     * Writes the log buffer plus any durable crash reports to a file the user can pull with adb.
     *
     * The crash reports are the point: the in-memory buffer is destroyed when the crash guard kills
     * the process, so without them a crash export would contain everything except the crash. A
     * device/OS/app header is prepended so a user-sent log identifies which Fire TV produced it.
     */
    fun exportLogs() {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(context.getExternalFilesDir(null) ?: context.filesDir, "torfilx-log.txt")
                    file.writeText(buildLogExport())
                    "Logs written to ${file.absolutePath}"
                }.getOrElse {
                    TorfilxLog.w(TAG, "Log export failed", it)
                    "Could not write logs: ${it.message}"
                }
            }
            say(MessageSection.ABOUT, text)
        }
    }

    private fun buildLogExport(): String = buildString {
        appendLine("TORFILX log export")
        appendLine(
            "device: ${Build.MANUFACTURER} ${Build.MODEL} · " +
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
                "abi ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}",
        )
        appendLine("app: ${about.appVersion}")
        val inUse = uiState.value.catalogue.inUse
        appendLine("catalogue: ${inUse.origin} ${inUse.version} · ${inUse.titleCount} titles")
        appendLine()
        val crashes = crashStore.readAll()
        if (crashes.isNotBlank()) {
            appendLine("---- crash reports (newest first) ----")
            appendLine(crashes)
            appendLine()
        }
        appendLine("---- log ----")
        append(TorfilxLog.dump())
    }

    /**
     * Writes watch progress + My List to a JSON file the user can copy off the device.
     *
     * Fire OS has no cloud backup, so this is the only way that data survives a reinstall or a stick
     * swap: back it up, copy the file off (adb pull, or a file manager), and restore after
     * reinstalling.
     */
    fun backupUserData() {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    val file = backupFile()
                    file.writeText(userDataBackup.exportToJson())
                    "Watch data backed up to ${file.absolutePath}. Copy it somewhere safe."
                }.getOrElse {
                    TorfilxLog.w(TAG, "Backup failed", it)
                    "Could not back up watch data: ${it.message}"
                }
            }
            say(MessageSection.WATCH_DATA, text)
        }
    }

    /** Restores watch progress + My List from the backup file, if present. */
    fun restoreUserData() {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    val file = backupFile()
                    if (!file.exists()) {
                        "No backup found at ${file.absolutePath}. Copy your backup file there first."
                    } else {
                        val result = userDataBackup.importFromJson(file.readText())
                        "Restored ${result.progressRestored} watch positions and " +
                            "${result.myListRestored} saved titles."
                    }
                }.getOrElse {
                    TorfilxLog.w(TAG, "Restore failed", it)
                    "Could not restore watch data: ${it.message}"
                }
            }
            say(MessageSection.WATCH_DATA, text)
        }
    }

    private fun backupFile(): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "torfilx-backup.json")

    fun dismissMessage() {
        message.value = null
    }

    private fun say(section: MessageSection, text: String) {
        message.value = SettingsMessage(text, section)
    }

    private fun launchSetting(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    /**
     * The installed build, read from the package manager rather than BuildConfig: this module has no
     * BuildConfig, and what is installed is the question being asked.
     */
    private fun readAboutInfo(): AboutInfo {
        val appVersion = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "${info.versionName} (build $code)"
        }.getOrDefault("unknown")
        return AboutInfo(
            appVersion = appVersion,
            device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            system = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
                (Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown ABI"),
        )
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        /** The disk-share choices offered, as a fraction of free space. */
        val STORAGE_CHOICES = listOf(0.25f, 0.5f, 0.75f)
    }
}
