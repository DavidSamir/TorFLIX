package com.torfilx.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.torfilx.core.common.di.Dispatcher
import com.torfilx.core.common.di.TorfilxDispatcher
import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.core.data.catalog.CatalogueUpdatePrefs
import com.torfilx.core.data.catalog.CatalogueUpdateRecord
import com.torfilx.core.data.catalog.CatalogueUpdateSettings
import com.torfilx.core.data.catalog.RejectedCatalogue
import com.torfilx.core.model.AppSettings
import com.torfilx.core.model.AspectPreference
import com.torfilx.core.model.AutoplayCountdown
import com.torfilx.core.model.LibrarySort
import com.torfilx.core.model.MetadataTimeout
import com.torfilx.core.model.QualityPreference
import com.torfilx.core.model.SeekStep
import com.torfilx.core.model.StreamingMode
import com.torfilx.core.model.SubtitleSize
import com.torfilx.core.model.SubtitleStyle
import com.torfilx.core.model.UploadLimit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Settings"

/**
 * User settings (plan.md §8.3), plus the small record the catalogue updater keeps.
 *
 * Nothing here touches disk while the object is being constructed. Hilt builds this singleton on the
 * main thread inside `MainActivity.onCreate`, and opening a DataStore there cost a Fire OS 5 stick
 * its first frame: the activity never finished `onCreate`, so the app showed a black screen forever.
 * The store is created on first *collection* instead, on [ioDispatcher].
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(TorfilxDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : CatalogueUpdatePrefs, LibraryPreferences {

    private val dataStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(ioDispatcher + SupervisorJob()),
            // Without this a corrupt preferences file throws on every read AND write: the reads are
            // caught below and fall back to defaults, but the writes were not, so the user could never
            // change a setting again. Replacing the corrupt file with empty preferences turns a
            // permanent lockout into a one-time reset to defaults.
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            produceFile = { context.preferencesDataStoreFile("torfilx_settings") },
        )
    }

    /**
     * Cold: [dataStore] is not built until something collects, which never happens on the main
     * thread. Every flow below is derived from this one.
     */
    private val preferences: Flow<Preferences> = flow { emitAll(dataStore.data) }

    /** [preferences], falling back to defaults when the file cannot be read. */
    private val safePreferences: Flow<Preferences> = preferences.catch { emit(emptyPreferences()) }

    private object Keys {
        val AUDIO_LANGUAGE = stringPreferencesKey("audio_language")
        val SUBTITLE_LANGUAGE = stringPreferencesKey("subtitle_language")
        val SUBTITLES_ON = booleanPreferencesKey("subtitles_on")
        val AUTOPLAY_NEXT = booleanPreferencesKey("autoplay_next")
        val QUALITY = stringPreferencesKey("quality")
        val FRAME_RATE_MATCHING = booleanPreferencesKey("frame_rate_matching")
        val TUNNELED_PLAYBACK = booleanPreferencesKey("tunneled_playback")
        val SKIP_INTRO_AUTO = booleanPreferencesKey("skip_intro_auto")
        val SHARING_CONSENT = booleanPreferencesKey("sharing_consent")
        val SHARING_CONSENT_SEEN = booleanPreferencesKey("sharing_consent_seen")
        val SHARING_LAUNCH_ASKED = booleanPreferencesKey("sharing_launch_asked")
        val SEEDING_ENABLED = booleanPreferencesKey("seeding_enabled")
        val STORAGE_FRACTION = stringPreferencesKey("storage_fraction")
        val USE_DHT = booleanPreferencesKey("use_dht")
        val USE_EXTRA_TRACKERS = booleanPreferencesKey("use_extra_trackers")
        val METADATA_TIMEOUT = stringPreferencesKey("metadata_timeout")
        val STREAMING_MODE = stringPreferencesKey("streaming_mode")
        val FORCE_SOFTWARE_DECODER = booleanPreferencesKey("force_software_decoder")
        val SEEK_STEP = stringPreferencesKey("seek_step")
        val AUTOPLAY_COUNTDOWN = stringPreferencesKey("autoplay_countdown")
        val DEFAULT_ASPECT = stringPreferencesKey("default_aspect")
        val SHOW_STREAM_STATS = booleanPreferencesKey("show_stream_stats")
        val SUBTITLE_SIZE = stringPreferencesKey("subtitle_size")
        val SUBTITLE_STYLE = stringPreferencesKey("subtitle_style")
        val UPLOAD_LIMIT = stringPreferencesKey("upload_limit")
        val LIBRARY_SORT = stringPreferencesKey("library_sort")
        val HIDE_WATCHED = booleanPreferencesKey("hide_watched")
        val REDUCE_MOTION = booleanPreferencesKey("reduce_motion")

        // Catalogue updates over the peer network.
        val CATALOG_UPDATES_ENABLED = booleanPreferencesKey("catalog_updates_enabled")
        val CATALOG_LAST_CHECK_MS = longPreferencesKey("catalog_last_check_ms")
        val CATALOG_LAST_RESULT = stringPreferencesKey("catalog_last_result")
        val CATALOG_LAST_SUCCESS_MS = longPreferencesKey("catalog_last_success_ms")
        val CATALOG_REJECTED_VERSION = longPreferencesKey("catalog_rejected_version")
        val CATALOG_REJECTED_APP_VERSION = intPreferencesKey("catalog_rejected_app_version")

        /**
         * What "Reset all settings" clears.
         *
         * Not the sharing consent: that is a decision about uploading and exposing the viewer's
         * address, and a reset of preferences must not quietly take it back or re-grant it. Not the
         * catalogue updater's bookkeeping either, which is a record rather than a preference.
         */
        val RESETTABLE: List<Preferences.Key<*>> = listOf(
            AUDIO_LANGUAGE, SUBTITLE_LANGUAGE, SUBTITLES_ON, AUTOPLAY_NEXT, QUALITY,
            FRAME_RATE_MATCHING, TUNNELED_PLAYBACK, SKIP_INTRO_AUTO, SEEDING_ENABLED, STORAGE_FRACTION,
            USE_DHT, USE_EXTRA_TRACKERS, METADATA_TIMEOUT, STREAMING_MODE, FORCE_SOFTWARE_DECODER,
            SEEK_STEP, AUTOPLAY_COUNTDOWN, DEFAULT_ASPECT, SHOW_STREAM_STATS, SUBTITLE_SIZE,
            SUBTITLE_STYLE, UPLOAD_LIMIT, LIBRARY_SORT, HIDE_WATCHED, REDUCE_MOTION,
            CATALOG_UPDATES_ENABLED,
        )
    }

    val settings: Flow<AppSettings> = preferences
        .catch { throwable ->
            // A corrupted preferences file must not brick the app; fall back to defaults.
            if (throwable is IOException) {
                TorfilxLog.w(TAG, "Settings unreadable, using defaults", throwable)
                emit(emptyPreferences())
            } else {
                throw throwable
            }
        }
        .map { prefs ->
            AppSettings(
                preferredAudioLanguage = prefs[Keys.AUDIO_LANGUAGE],
                preferredSubtitleLanguage = prefs[Keys.SUBTITLE_LANGUAGE],
                subtitlesEnabledByDefault = prefs[Keys.SUBTITLES_ON] ?: false,
                autoplayNextEpisode = prefs[Keys.AUTOPLAY_NEXT] ?: true,
                quality = prefs[Keys.QUALITY]?.let { name ->
                    runCatching { QualityPreference.valueOf(name) }.getOrNull()
                } ?: QualityPreference.AUTO,
                frameRateMatching = prefs[Keys.FRAME_RATE_MATCHING] ?: true,
                tunneledPlayback = prefs[Keys.TUNNELED_PLAYBACK] ?: true,
                skipIntroAutomatically = prefs[Keys.SKIP_INTRO_AUTO] ?: false,
                useDht = prefs[Keys.USE_DHT] ?: true,
                useExtraTrackers = prefs[Keys.USE_EXTRA_TRACKERS] ?: true,
                metadataTimeout = prefs[Keys.METADATA_TIMEOUT]?.let { name ->
                    runCatching { MetadataTimeout.valueOf(name) }.getOrNull()
                } ?: MetadataTimeout.STANDARD,
                streamingMode = prefs[Keys.STREAMING_MODE]?.let { name ->
                    runCatching { StreamingMode.valueOf(name) }.getOrNull()
                } ?: StreamingMode.SEQUENTIAL,
                forceSoftwareDecoder = prefs[Keys.FORCE_SOFTWARE_DECODER] ?: false,
                seekStep = prefs.enumOrNull(Keys.SEEK_STEP) ?: SeekStep.TEN,
                autoplayCountdown = prefs.enumOrNull(Keys.AUTOPLAY_COUNTDOWN) ?: AutoplayCountdown.STANDARD,
                defaultAspect = prefs.enumOrNull(Keys.DEFAULT_ASPECT) ?: AspectPreference.FIT,
                showStreamStats = prefs[Keys.SHOW_STREAM_STATS] ?: false,
                subtitleSize = prefs.enumOrNull(Keys.SUBTITLE_SIZE) ?: SubtitleSize.TV_DEFAULT,
                subtitleStyle = prefs.enumOrNull(Keys.SUBTITLE_STYLE) ?: SubtitleStyle.TV_DEFAULT,
                // An unreadable value falls back to the floor, never below it.
                uploadLimit = prefs.enumOrNull(Keys.UPLOAD_LIMIT) ?: UploadLimit.STANDARD,
                librarySort = prefs.enumOrNull(Keys.LIBRARY_SORT) ?: LibrarySort.DEFAULT,
                hideWatched = prefs[Keys.HIDE_WATCHED] ?: false,
                reduceMotion = prefs[Keys.REDUCE_MOTION] ?: false,
            )
        }

    override val libraryDefaults: Flow<LibraryDefaults> = settings
        .map { LibraryDefaults(sort = it.librarySort, hideWatched = it.hideWatched) }
        .distinctUntilChanged()

    /** Only the reduce-motion switch, so the whole UI is not recomposed when anything else changes. */
    val reduceMotion: Flow<Boolean> = settings.map { it.reduceMotion }.distinctUntilChanged()

    /**
     * Whether the user has agreed to share (upload) while watching over BitTorrent.
     *
     * Default false, and nothing torrent-related runs until it is true: uploading redistributes
     * whatever is being watched, which is the user's call, not the app's.
     */
    val sharingConsent: Flow<Boolean> = safePreferences.map { it[Keys.SHARING_CONSENT] ?: false }

    /** True once the first-run sharing screen has been answered either way. */
    val sharingConsentAnswered: Flow<Boolean> = safePreferences.map { it[Keys.SHARING_CONSENT_SEEN] ?: false }

    /**
     * Whether the app must open on the sharing question: until sharing is accepted, nothing else of the
     * app is shown.
     *
     * Sharing is a condition of using the app, since every title plays over BitTorrent. So this follows
     * the consent alone, not whether the question was put before: a viewer who said "Not now" in an
     * earlier build is asked again, and can accept or leave.
     */
    val askSharingAtLaunch: Flow<Boolean> = safePreferences.map { prefs -> prefs[Keys.SHARING_CONSENT] != true }

    /** Records that sharing was accepted at launch. */
    suspend fun acceptSharingAtLaunch() = edit {
        it[Keys.SHARING_CONSENT] = true
        it[Keys.SHARING_CONSENT_SEEN] = true
        it[Keys.SHARING_LAUNCH_ASKED] = true
    }

    /** Keep seeding after playback finishes, within the storage budget. */
    val seedingEnabled: Flow<Boolean> = safePreferences.map { it[Keys.SEEDING_ENABLED] ?: true }

    /** Fraction of free space the torrent cache may use. */
    val storageFraction: Flow<Float> = safePreferences
        .map { it[Keys.STORAGE_FRACTION]?.toFloatOrNull() ?: DEFAULT_STORAGE_FRACTION }

    suspend fun setAudioLanguage(language: String?) = editNullable(Keys.AUDIO_LANGUAGE, language)
    suspend fun setSubtitleLanguage(language: String?) = editNullable(Keys.SUBTITLE_LANGUAGE, language)
    suspend fun setSubtitlesEnabled(enabled: Boolean) = edit { it[Keys.SUBTITLES_ON] = enabled }
    suspend fun setAutoplayNext(enabled: Boolean) = edit { it[Keys.AUTOPLAY_NEXT] = enabled }
    suspend fun setQuality(preference: QualityPreference) = edit { it[Keys.QUALITY] = preference.name }
    suspend fun setFrameRateMatching(enabled: Boolean) = edit { it[Keys.FRAME_RATE_MATCHING] = enabled }
    suspend fun setTunneledPlayback(enabled: Boolean) = edit { it[Keys.TUNNELED_PLAYBACK] = enabled }
    suspend fun setSkipIntroAutomatically(enabled: Boolean) = edit { it[Keys.SKIP_INTRO_AUTO] = enabled }
    suspend fun setSharingConsent(consented: Boolean) = edit {
        it[Keys.SHARING_CONSENT] = consented
        it[Keys.SHARING_CONSENT_SEEN] = true
    }

    suspend fun setSeedingEnabled(enabled: Boolean) = edit { it[Keys.SEEDING_ENABLED] = enabled }

    suspend fun setStorageFraction(fraction: Float) = edit {
        it[Keys.STORAGE_FRACTION] = fraction.coerceIn(0.1f, 0.9f).toString()
    }

    suspend fun setUseDht(enabled: Boolean) = edit { it[Keys.USE_DHT] = enabled }
    suspend fun setUseExtraTrackers(enabled: Boolean) = edit { it[Keys.USE_EXTRA_TRACKERS] = enabled }
    suspend fun setMetadataTimeout(value: MetadataTimeout) = edit { it[Keys.METADATA_TIMEOUT] = value.name }
    suspend fun setStreamingMode(mode: StreamingMode) = edit { it[Keys.STREAMING_MODE] = mode.name }
    suspend fun setForceSoftwareDecoder(enabled: Boolean) =
        edit { it[Keys.FORCE_SOFTWARE_DECODER] = enabled }

    suspend fun setSeekStep(step: SeekStep) = edit { it[Keys.SEEK_STEP] = step.name }
    suspend fun setAutoplayCountdown(value: AutoplayCountdown) = edit { it[Keys.AUTOPLAY_COUNTDOWN] = value.name }
    suspend fun setDefaultAspect(value: AspectPreference) = edit { it[Keys.DEFAULT_ASPECT] = value.name }
    suspend fun setShowStreamStats(enabled: Boolean) = edit { it[Keys.SHOW_STREAM_STATS] = enabled }
    suspend fun setSubtitleSize(size: SubtitleSize) = edit { it[Keys.SUBTITLE_SIZE] = size.name }
    suspend fun setSubtitleStyle(style: SubtitleStyle) = edit { it[Keys.SUBTITLE_STYLE] = style.name }
    suspend fun setUploadLimit(limit: UploadLimit) = edit { it[Keys.UPLOAD_LIMIT] = limit.name }
    suspend fun setLibrarySort(sort: LibrarySort) = edit { it[Keys.LIBRARY_SORT] = sort.name }
    suspend fun setHideWatched(enabled: Boolean) = edit { it[Keys.HIDE_WATCHED] = enabled }
    suspend fun setReduceMotion(enabled: Boolean) = edit { it[Keys.REDUCE_MOTION] = enabled }

    /** Puts every preference back to its default. Sharing consent is kept; see [Keys.RESETTABLE]. */
    suspend fun resetToDefaults() = edit { prefs -> Keys.RESETTABLE.forEach { key -> prefs -= key } }

    // --- Catalogue updates -----------------------------------------------------------------------

    override val catalogUpdatesEnabled: Flow<Boolean> =
        safePreferences.map { it[Keys.CATALOG_UPDATES_ENABLED] ?: true }

    suspend fun setCatalogUpdatesEnabled(enabled: Boolean) = edit { it[Keys.CATALOG_UPDATES_ENABLED] = enabled }

    override val catalogueUpdateRecord: Flow<CatalogueUpdateRecord> = safePreferences.map { prefs ->
        CatalogueUpdateRecord(
            lastCheckMs = prefs[Keys.CATALOG_LAST_CHECK_MS],
            lastResult = prefs[Keys.CATALOG_LAST_RESULT],
            lastSuccessMs = prefs[Keys.CATALOG_LAST_SUCCESS_MS],
        )
    }

    override suspend fun catalogueUpdateSettings(): CatalogueUpdateSettings {
        val prefs = safePreferences.first()
        return CatalogueUpdateSettings(
            updatesEnabled = prefs[Keys.CATALOG_UPDATES_ENABLED] ?: true,
            sharingConsent = prefs[Keys.SHARING_CONSENT] ?: false,
            useDht = prefs[Keys.USE_DHT] ?: true,
        )
    }

    override suspend fun recordCatalogueCheck(atMs: Long, result: String, successful: Boolean) = edit { prefs ->
        prefs[Keys.CATALOG_LAST_CHECK_MS] = atMs
        prefs[Keys.CATALOG_LAST_RESULT] = result
        if (successful) prefs[Keys.CATALOG_LAST_SUCCESS_MS] = atMs
    }

    override suspend fun recordRejectedCatalogue(version: Long, appVersionCode: Int) = edit { prefs ->
        prefs[Keys.CATALOG_REJECTED_VERSION] = version
        prefs[Keys.CATALOG_REJECTED_APP_VERSION] = appVersionCode
    }

    override suspend fun rejectedCatalogue(): RejectedCatalogue? {
        val prefs = safePreferences.first()
        val version = prefs[Keys.CATALOG_REJECTED_VERSION] ?: return null
        val appVersionCode = prefs[Keys.CATALOG_REJECTED_APP_VERSION] ?: return null
        return RejectedCatalogue(version, appVersionCode)
    }

    // Synchronous snapshots for the torrent engine and player factory, which read configuration from
    // inside native callbacks and construction paths that cannot suspend. Kept current by the layer
    // that observes the flows above (see TorrentCoordinator).
    var cachedSharingConsent: Boolean = false
        internal set
    var cachedUseDht: Boolean = true
        internal set
    var cachedUseExtraTrackers: Boolean = true
        internal set
    var cachedMetadataTimeoutSeconds: Int = MetadataTimeout.STANDARD.seconds
        internal set
    var cachedUploadLimitBytes: Int = UploadLimit.STANDARD.bytesPerSecond
        internal set

    companion object {
        const val DEFAULT_STORAGE_FRACTION = 0.5f
    }

    // Writes are moved to the IO dispatcher explicitly: a setter called from a screen runs on the
    // main thread, and that is where the store would otherwise be opened for the first time.
    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        withContext(ioDispatcher) { dataStore.edit(block) }
    }

    /** The stored enum constant, or null when it is missing or names one a later build removed. */
    private inline fun <reified T : Enum<T>> Preferences.enumOrNull(key: Preferences.Key<String>): T? =
        this[key]?.let { name -> runCatching { enumValueOf<T>(name) }.getOrNull() }

    private suspend fun editNullable(key: Preferences.Key<String>, value: String?) {
        withContext(ioDispatcher) {
            dataStore.edit { prefs ->
                if (value.isNullOrBlank()) prefs.remove(key) else prefs[key] = value
            }
        }
    }
}
