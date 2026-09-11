package com.torfilx.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.core.data.catalog.CatalogUpdateState
import com.torfilx.core.data.catalog.CatalogUpdater
import com.torfilx.core.data.catalog.CatalogueInfo
import com.torfilx.core.data.catalog.CatalogueUpdateRecord
import com.torfilx.core.data.repository.MediaRepository
import com.torfilx.core.data.settings.SettingsRepository
import com.torfilx.core.data.torrent.TorrentCoordinator
import com.torfilx.core.model.AppSettings
import com.torfilx.core.model.MetadataTimeout
import com.torfilx.core.model.QualityPreference
import com.torfilx.core.model.StreamingMode
import com.torfilx.core.torrent.SharingStats
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val sharingConsent: Boolean = false,
    val seedingEnabled: Boolean = true,
    val storageFraction: Float = 0.5f,
    val sharingStats: SharingStats = SharingStats(),
    val torrentAvailable: Boolean = false,
    val catalogue: CatalogueSettingsState = CatalogueSettingsState(),
    val message: String? = null,
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
    private val torrentCoordinator: TorrentCoordinator,
    private val catalogUpdater: CatalogUpdater,
    private val crashStore: com.torfilx.core.common.log.CrashStore,
    private val userDataBackup: com.torfilx.core.data.backup.UserDataBackup,
) : ViewModel() {

    private val message = MutableStateFlow<String?>(null)
    private val bundledVersion = MutableStateFlow(0L)

    private val catalogue: kotlinx.coroutines.flow.Flow<CatalogueSettingsState> = combine(
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

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        combine(
            settingsRepository.sharingConsent,
            settingsRepository.seedingEnabled,
            settingsRepository.storageFraction,
            torrentCoordinator.stats,
        ) { consent, seeding, fraction, stats -> SharingSnapshot(consent, seeding, fraction, stats) },
        catalogue,
        message,
    ) { settings, sharing, catalogueState, msg ->
        SettingsUiState(
            settings = settings,
            sharingConsent = sharing.consent,
            seedingEnabled = sharing.seeding,
            storageFraction = sharing.fraction,
            sharingStats = sharing.stats,
            torrentAvailable = torrentCoordinator.isAvailable(),
            catalogue = catalogueState,
            message = msg,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SettingsUiState())

    private data class SharingSnapshot(
        val consent: Boolean,
        val seeding: Boolean,
        val fraction: Float,
        val stats: SharingStats,
    )

    init {
        viewModelScope.launch { bundledVersion.value = catalogUpdater.bundledCatalogueVersion() }
    }

    /** Turning sharing off stops uploading immediately and makes torrent playback unavailable. */
    fun setSharingConsent(consented: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSharingConsent(consented)
            message.value = if (consented) {
                "Sharing is on. You upload what you are watching, within the storage limit."
            } else {
                "Sharing is off. Playback is unavailable until you turn it back on."
            }
        }
    }

    fun setSeedingEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setSeedingEnabled(enabled) }
    }

    /** Cycles the share of free disk the torrent cache may use. */
    fun cycleStorageFraction() {
        viewModelScope.launch {
            val next = when (uiState.value.storageFraction) {
                0.25f -> 0.5f
                0.5f -> 0.75f
                else -> 0.25f
            }
            settingsRepository.setStorageFraction(next)
        }
    }

    // --- Catalogue -------------------------------------------------------------------------------

    fun setCatalogUpdatesEnabled(enabled: Boolean) =
        launchSetting { settingsRepository.setCatalogUpdatesEnabled(enabled) }

    /** Looks for a newer catalogue now. Starts the peer network if sharing is on and it is not running. */
    fun checkCatalogueNow() {
        if (!catalogUpdater.checkNow()) message.value = "A catalogue check is already running."
    }

    /** Drops the downloaded catalogue and goes back to the one inside the app. */
    fun useBundledCatalogue() {
        viewModelScope.launch {
            runCatching { catalogUpdater.resetToBundled() }
                .onSuccess {
                    message.value = "Back on the built-in catalogue. The catalogue you left will not be " +
                        "downloaded again; a newer one still will."
                }
                .onFailure {
                    TorfilxLog.w(TAG, "Could not go back to the built-in catalogue", it)
                    message.value = "Could not switch catalogues: ${it.message}"
                }
        }
    }

    fun setAudioLanguage(language: String?) = launchSetting { settingsRepository.setAudioLanguage(language) }
    fun setSubtitleLanguage(language: String?) = launchSetting { settingsRepository.setSubtitleLanguage(language) }
    fun setSubtitlesEnabled(enabled: Boolean) = launchSetting { settingsRepository.setSubtitlesEnabled(enabled) }
    fun setAutoplayNext(enabled: Boolean) = launchSetting { settingsRepository.setAutoplayNext(enabled) }
    fun setQuality(preference: QualityPreference) = launchSetting { settingsRepository.setQuality(preference) }
    fun setFrameRateMatching(enabled: Boolean) = launchSetting { settingsRepository.setFrameRateMatching(enabled) }
    fun setTunneledPlayback(enabled: Boolean) = launchSetting { settingsRepository.setTunneledPlayback(enabled) }
    fun setSkipIntroAutomatically(enabled: Boolean) =
        launchSetting { settingsRepository.setSkipIntroAutomatically(enabled) }

    fun setUseDht(enabled: Boolean) = launchSetting { settingsRepository.setUseDht(enabled) }
    fun setUseExtraTrackers(enabled: Boolean) =
        launchSetting { settingsRepository.setUseExtraTrackers(enabled) }
    fun setMetadataTimeout(value: MetadataTimeout) =
        launchSetting { settingsRepository.setMetadataTimeout(value) }
    fun setStreamingMode(mode: StreamingMode) = launchSetting { settingsRepository.setStreamingMode(mode) }
    fun setForceSoftwareDecoder(enabled: Boolean) =
        launchSetting { settingsRepository.setForceSoftwareDecoder(enabled) }

    /** Clears downloaded torrent data; watch progress and My List are kept. */
    fun clearDownloadedData() {
        viewModelScope.launch {
            runCatching { torrentCoordinator.clearAllData() }
                .onSuccess { message.value = "Downloaded data cleared. Watch progress was kept." }
                .onFailure { message.value = "Could not clear downloaded data: ${it.message}" }
        }
    }

    fun clearSearchHistory() {
        viewModelScope.launch {
            mediaRepository.clearSearchHistory()
            message.value = "Search history cleared."
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
            message.value = withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(context.getExternalFilesDir(null) ?: context.filesDir, "torfilx-log.txt")
                    file.writeText(buildLogExport())
                    "Logs written to ${file.absolutePath}"
                }.getOrElse {
                    TorfilxLog.w(TAG, "Log export failed", it)
                    "Could not write logs: ${it.message}"
                }
            }
        }
    }

    private fun buildLogExport(): String = buildString {
        appendLine("TORFILX log export")
        appendLine(
            "device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · " +
                "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) · " +
                "abi ${android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}",
        )
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
            message.value = withContext(Dispatchers.IO) {
                runCatching {
                    val file = backupFile()
                    file.writeText(userDataBackup.exportToJson())
                    "Watch data backed up to ${file.absolutePath}. Copy it somewhere safe."
                }.getOrElse {
                    TorfilxLog.w(TAG, "Backup failed", it)
                    "Could not back up watch data: ${it.message}"
                }
            }
        }
    }

    /** Restores watch progress + My List from the backup file, if present. */
    fun restoreUserData() {
        viewModelScope.launch {
            message.value = withContext(Dispatchers.IO) {
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
        }
    }

    private fun backupFile(): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "torfilx-backup.json")

    fun dismissMessage() {
        message.value = null
    }

    private fun launchSetting(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
