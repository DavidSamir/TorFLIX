package com.torfilx.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.torfilx.core.data.catalog.CatalogUpdateState
import com.torfilx.core.data.catalog.CatalogueOrigin
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
import com.torfilx.core.model.TransferTotals
import com.torfilx.core.model.UploadLimit
import com.torfilx.core.ui.component.KeyboardLayout
import com.torfilx.core.ui.component.OnScreenKeyboard
import com.torfilx.core.ui.component.SearchField
import com.torfilx.core.ui.component.TvButton
import com.torfilx.core.ui.component.TvChip
import com.torfilx.core.ui.component.formatBytes
import com.torfilx.core.ui.theme.LocalTorfilxDimens
import com.torfilx.core.ui.theme.TorfilxColors
import com.torfilx.core.ui.util.Format
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Which text field the on-screen keyboard is currently editing. */
private enum class EditingField { NONE, AUDIO_LANGUAGE, SUBTITLE_LANGUAGE }

/**
 * Languages offered as chips, by ISO 639-1 code: the code is what the player matches tracks on.
 * Anything else can still be typed with "Other…".
 */
private val COMMON_LANGUAGES = listOf(
    "en" to "English",
    "he" to "Hebrew",
    "ar" to "Arabic",
    "es" to "Spanish",
    "fr" to "French",
    "de" to "German",
    "it" to "Italian",
    "pt" to "Portuguese",
    "ru" to "Russian",
    "nl" to "Dutch",
    "pl" to "Polish",
    "tr" to "Turkish",
    "hi" to "Hindi",
    "ja" to "Japanese",
    "ko" to "Korean",
    "zh" to "Chinese",
)

/** How long a destructive button waits for its second press before it forgets the first. */
private const val CONFIRM_WINDOW_MS = 4_000L

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val dimens = LocalTorfilxDimens.current

    var editing by remember { mutableStateOf(EditingField.NONE) }
    var buffer by remember { mutableStateOf("") }
    var keyboardLayout by remember { mutableStateOf(KeyboardLayout.LATIN) }

    // Back closes the text editor first, then leaves the screen.
    androidx.activity.compose.BackHandler(enabled = true) {
        if (editing != EditingField.NONE) editing = EditingField.NONE else onBack()
    }

    fun startEditing(field: EditingField, initial: String) {
        editing = field
        buffer = initial
        keyboardLayout = KeyboardLayout.LATIN
    }

    fun commit() {
        when (editing) {
            EditingField.AUDIO_LANGUAGE -> viewModel.setAudioLanguage(buffer)
            EditingField.SUBTITLE_LANGUAGE -> viewModel.setSubtitleLanguage(buffer)
            EditingField.NONE -> Unit
        }
        editing = EditingField.NONE
        buffer = ""
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(TorfilxColors.Background)
            .padding(horizontal = dimens.overscanHorizontal, vertical = dimens.overscanVertical),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .focusGroup()
                .focusRestorer(),
            contentPadding = PaddingValues(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item(key = "title") {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineLarge,
                    color = TorfilxColors.TextPrimary,
                )
            }

            item(key = "playback") {
                SettingsSection("Playback") {
                    SettingsToggleRow(
                        label = "Autoplay next episode",
                        description = "Also starts finding peers for the next episode while this one ends.",
                        checked = state.settings.autoplayNextEpisode,
                        onToggle = viewModel::setAutoplayNext,
                    )
                    if (state.settings.autoplayNextEpisode) {
                        ChoiceRow(
                            label = "Countdown before the next episode",
                            options = AutoplayCountdown.entries,
                            selected = state.settings.autoplayCountdown,
                            optionLabel = { it.label },
                            onSelect = viewModel::setAutoplayCountdown,
                        )
                    }
                    SettingsToggleRow(
                        label = "Skip intros automatically",
                        checked = state.settings.skipIntroAutomatically,
                        onToggle = viewModel::setSkipIntroAutomatically,
                    )
                    SettingsToggleRow(
                        label = "Match display frame rate",
                        description = "Switches the TV to 24/50/60 Hz to stop film judder.",
                        checked = state.settings.frameRateMatching,
                        onToggle = viewModel::setFrameRateMatching,
                    )
                    SettingsToggleRow(
                        label = "Tunneled playback",
                        description = "Recommended for 4K/HDR. Turn off if your AV receiver glitches.",
                        checked = state.settings.tunneledPlayback,
                        onToggle = viewModel::setTunneledPlayback,
                    )
                    ChoiceRow(
                        label = "Quality",
                        options = QualityPreference.entries,
                        selected = state.settings.quality,
                        optionLabel = { it.label() },
                        onSelect = viewModel::setQuality,
                    )
                    ChoiceRow(
                        label = "Skip with ← and →",
                        description = "Rewind and fast-forward on the remote jump three times as far.",
                        options = SeekStep.entries,
                        selected = state.settings.seekStep,
                        optionLabel = { it.label },
                        onSelect = viewModel::setSeekStep,
                    )
                    ChoiceRow(
                        label = "Picture size for new titles",
                        description = "Fit shows the whole picture, Fill stretches it, Zoom crops the edges. " +
                            "Changing it in the player lasts until you leave.",
                        options = AspectPreference.entries,
                        selected = state.settings.defaultAspect,
                        optionLabel = { it.label },
                        onSelect = viewModel::setDefaultAspect,
                    )
                    SettingsToggleRow(
                        label = "Show stream stats while playing",
                        description = "Speed, peers and download progress in the corner of the screen.",
                        checked = state.settings.showStreamStats,
                        onToggle = viewModel::setShowStreamStats,
                    )
                }
            }

            item(key = "languages") {
                SettingsSection("Subtitles and language") {
                    LanguageRow(
                        label = "Preferred audio language",
                        noneLabel = "Original",
                        current = state.settings.preferredAudioLanguage,
                        onSelect = viewModel::setAudioLanguage,
                        onOther = {
                            startEditing(EditingField.AUDIO_LANGUAGE, state.settings.preferredAudioLanguage.orEmpty())
                        },
                    )
                    LanguageRow(
                        label = "Preferred subtitle language",
                        noneLabel = "None",
                        current = state.settings.preferredSubtitleLanguage,
                        onSelect = viewModel::setSubtitleLanguage,
                        onOther = {
                            startEditing(
                                EditingField.SUBTITLE_LANGUAGE,
                                state.settings.preferredSubtitleLanguage.orEmpty(),
                            )
                        },
                    )
                    SettingsToggleRow(
                        label = "Subtitles on by default",
                        checked = state.settings.subtitlesEnabledByDefault,
                        onToggle = viewModel::setSubtitlesEnabled,
                    )
                    ChoiceRow(
                        label = "Subtitle size",
                        description = "TV default follows the TV's own caption settings.",
                        options = SubtitleSize.entries,
                        selected = state.settings.subtitleSize,
                        optionLabel = { it.label },
                        onSelect = viewModel::setSubtitleSize,
                    )
                    ChoiceRow(
                        label = "Subtitle style",
                        options = SubtitleStyle.entries,
                        selected = state.settings.subtitleStyle,
                        optionLabel = { it.label },
                        onSelect = viewModel::setSubtitleStyle,
                    )
                }
            }

            item(key = "library") {
                SettingsSection("Home and library") {
                    ChoiceRow(
                        label = "Sort Movies and Shows by",
                        options = LibrarySort.entries,
                        selected = state.settings.librarySort,
                        optionLabel = { it.label() },
                        onSelect = viewModel::setLibrarySort,
                    )
                    SettingsToggleRow(
                        label = "Hide watched titles",
                        description = "Movies and Shows open on unwatched titles. My List still shows everything.",
                        checked = state.settings.hideWatched,
                        onToggle = viewModel::setHideWatched,
                    )
                    SettingsToggleRow(
                        label = "Reduce motion",
                        description = "No banner auto-advance, no zoom on focus, no pulsing placeholders. " +
                            "Helps on slower sticks.",
                        checked = state.settings.reduceMotion,
                        onToggle = viewModel::setReduceMotion,
                    )
                }
            }

            item(key = "sharing") {
                SettingsSection("Sharing (BitTorrent)") {
                    if (!state.torrentAvailable) {
                        Text(
                            text = "BitTorrent is not available on this device.",
                            style = MaterialTheme.typography.labelLarge,
                            color = TorfilxColors.TextTertiary,
                        )
                    } else {
                        SettingsToggleRow(
                            label = "Share while watching",
                            description = "Streaming a title also uploads it to others, and your IP " +
                                "is visible to that swarm. Off by default.",
                            checked = state.sharingConsent,
                            onToggle = viewModel::setSharingConsent,
                        )
                        SettingsToggleRow(
                            label = "Keep seeding after playback",
                            description = "Keeps sharing what is already on disk until the space is needed.",
                            checked = state.seedingEnabled,
                            onToggle = viewModel::setSeedingEnabled,
                        )
                        ChoiceRow(
                            label = "Upload speed limit",
                            description = "How fast this TV may upload while sharing. 2 MB/s is the built-in " +
                                "limit and the lowest choice; you can only give more. Applies at once.",
                            options = UploadLimit.entries,
                            selected = state.settings.uploadLimit,
                            optionLabel = { it.label },
                            onSelect = viewModel::setUploadLimit,
                        )
                        ChoiceRow(
                            label = "Disk space for shared titles",
                            description = "A share of the free space. 500 MB is always left free.",
                            options = SettingsViewModel.STORAGE_CHOICES,
                            selected = SettingsViewModel.STORAGE_CHOICES
                                .firstOrNull { abs(it - state.storageFraction) < FRACTION_TOLERANCE },
                            optionLabel = { "${(it * PERCENT).toInt()}% of free" },
                            onSelect = viewModel::setStorageFraction,
                        )
                        Text(
                            text = with(state.sharingStats) {
                                "Using ${formatBytes(diskUsedBytes)} of ${formatBytes(diskCapBytes)} " +
                                    "(${formatBytes(freeSpaceBytes)} free)"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = TorfilxColors.TextSecondary,
                        )
                    }
                    MessageLine(state.message, MessageSection.SHARING)
                }
            }

            if (state.torrentAvailable) {
                item(key = "streamed") {
                    StreamedSection(
                        totals = state.streamed,
                        downloadRate = state.sharingStats.downloadRateBytesPerSecond,
                        uploadRate = state.sharingStats.uploadRateBytesPerSecond,
                        active = state.sharingStats.activeTorrents > 0,
                    )
                }
            }

            item(key = "catalogue") {
                CatalogueSection(
                    state = state.catalogue,
                    torrentAvailable = state.torrentAvailable,
                    message = state.message,
                    onToggleUpdates = viewModel::setCatalogUpdatesEnabled,
                    onCheckNow = viewModel::checkCatalogueNow,
                    onUseBundled = viewModel::useBundledCatalogue,
                )
            }

            item(key = "engine") {
                SettingsSection("Streaming engine") {
                    Text(
                        text = "Defaults suit most devices. Change these if a title will not start or " +
                            "stutters — they take effect the next time you press Play.",
                        style = MaterialTheme.typography.labelMedium,
                        color = TorfilxColors.TextSecondary,
                    )
                    SettingsToggleRow(
                        label = "Find peers with DHT",
                        description = "The distributed peer network. Turn off only if your network blocks it.",
                        checked = state.settings.useDht,
                        onToggle = viewModel::setUseDht,
                    )
                    SettingsToggleRow(
                        label = "Use extra public trackers",
                        description = "Adds well-known trackers so titles find peers even when a magnet's own are dead.",
                        checked = state.settings.useExtraTrackers,
                        onToggle = viewModel::setUseExtraTrackers,
                    )
                    SettingsToggleRow(
                        label = "Force software video decoding",
                        description = "Try this if video is black, glitchy, or refuses to play on this device.",
                        checked = state.settings.forceSoftwareDecoder,
                        onToggle = viewModel::setForceSoftwareDecoder,
                    )
                    ChoiceRow(
                        label = "Time to find a title",
                        options = MetadataTimeout.entries,
                        selected = state.settings.metadataTimeout,
                        optionLabel = { it.label },
                        onSelect = viewModel::setMetadataTimeout,
                    )
                    ChoiceRow(
                        label = "Streaming mode",
                        options = StreamingMode.entries,
                        selected = state.settings.streamingMode,
                        optionLabel = { it.label },
                        onSelect = viewModel::setStreamingMode,
                    )
                }
            }

            item(key = "userdata") {
                SettingsSection("Watch data") {
                    Text(
                        text = "Continue Watching and My List live only on this device. Back them up " +
                            "and copy the file off to keep them across a reinstall or a new stick.",
                        style = MaterialTheme.typography.labelMedium,
                        color = TorfilxColors.TextSecondary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvButton(text = "Back up watch data", onClick = viewModel::backupUserData, primary = false)
                        TvButton(text = "Restore watch data", onClick = viewModel::restoreUserData, primary = false)
                        ConfirmButton(
                            text = "Clear watch history",
                            confirmText = "Press again to clear",
                            onConfirm = viewModel::clearWatchHistory,
                        )
                    }
                    MessageLine(state.message, MessageSection.WATCH_DATA)
                }
            }

            item(key = "maintenance") {
                SettingsSection("Maintenance") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ConfirmButton(
                            text = "Clear downloaded data",
                            confirmText = "Press again to clear",
                            onConfirm = viewModel::clearDownloadedData,
                        )
                        TvButton(
                            text = "Clear search history",
                            onClick = viewModel::clearSearchHistory,
                            primary = false,
                        )
                        TvButton(text = "Export logs", onClick = viewModel::exportLogs, primary = false)
                    }
                    MessageLine(state.message, MessageSection.MAINTENANCE)
                }
            }

            item(key = "about") {
                AboutSection(state = state, onReset = viewModel::resetSettings)
            }
        }

        if (editing != EditingField.NONE) {
            Column(
                modifier = Modifier.width(400.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = when (editing) {
                        EditingField.AUDIO_LANGUAGE -> "Audio language code (e.g. en, he)"
                        EditingField.SUBTITLE_LANGUAGE -> "Subtitle language code"
                        EditingField.NONE -> ""
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = TorfilxColors.TextPrimary,
                )
                SearchField(value = buffer, placeholder = "Type using the keys below")
                OnScreenKeyboard(
                    onCharacter = { character ->
                        buffer += if (editing != EditingField.NONE) {
                            character.lowercaseChar()
                        } else {
                            character
                        }
                    },
                    onBackspace = { buffer = buffer.dropLast(1) },
                    onClear = { buffer = "" },
                    layout = keyboardLayout,
                    onLayoutChange = { keyboardLayout = it },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton(text = "Save", onClick = { commit() })
                    TvButton(
                        text = "Cancel",
                        onClick = { editing = EditingField.NONE },
                        primary = false,
                    )
                }
            }
        }
    }
}

/**
 * How much this TV has streamed in from peers and out to them.
 *
 * Text only, so it sits between two sections with controls: moving focus past it scrolls it into view.
 */
@Composable
private fun StreamedSection(totals: StreamedTotals, downloadRate: Int, uploadRate: Int, active: Boolean) {
    SettingsSection("Data streamed") {
        Text(
            text = "In is what this TV downloaded from peers to play; out is what it uploaded to others. " +
                "Counted since downloaded data was last cleared, and updated every 30 seconds while streaming.",
            style = MaterialTheme.typography.labelMedium,
            color = TorfilxColors.TextSecondary,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StreamedRow(label = "", inbound = "In", outbound = "Out", header = true)
            StreamedRow("Today", totals.today)
            StreamedRow("Last 30 days", totals.last30Days)
            StreamedRow("All time", totals.allTime)
            if (active) {
                StreamedRow(
                    label = "Right now",
                    inbound = Format.speed(downloadRate),
                    outbound = Format.speed(uploadRate),
                )
            }
        }
    }
}

@Composable
private fun StreamedRow(label: String, totals: TransferTotals) =
    StreamedRow(label = label, inbound = formatBytes(totals.downloadedBytes), outbound = formatBytes(totals.uploadedBytes))

@Composable
private fun StreamedRow(label: String, inbound: String, outbound: String, header: Boolean = false) {
    val style = if (header) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium
    val valueColor = if (header) TorfilxColors.TextSecondary else TorfilxColors.TextPrimary
    Row {
        Text(text = label, style = style, color = TorfilxColors.TextSecondary, modifier = Modifier.width(180.dp))
        Text(text = inbound, style = style, color = valueColor, modifier = Modifier.width(140.dp))
        Text(text = outbound, style = style, color = valueColor, modifier = Modifier.width(140.dp))
    }
}

/** The installed build, the device, and "Reset all settings" as the last control on the screen. */
@Composable
private fun AboutSection(state: SettingsUiState, onReset: () -> Unit) {
    SettingsSection("About") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AboutLine("App", "TORFILX ${state.about.appVersion}")
            AboutLine("Device", state.about.device)
            AboutLine("System", state.about.system)
            AboutLine("Catalogue", catalogueSummary(state.catalogue))
            AboutLine("BitTorrent", if (state.torrentAvailable) "Available" else "Not available on this device")
        }
        Text(
            text = "Reset puts every setting on this screen back to its default. Your sharing choice, " +
                "watch history and My List are kept.",
            style = MaterialTheme.typography.labelMedium,
            color = TorfilxColors.TextSecondary,
        )
        Row {
            ConfirmButton(text = "Reset all settings", confirmText = "Press again to reset", onConfirm = onReset)
        }
        MessageLine(state.message, MessageSection.ABOUT)
    }
}

@Composable
private fun AboutLine(label: String, value: String) {
    Row {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TorfilxColors.TextSecondary,
            modifier = Modifier.width(180.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = TorfilxColors.TextPrimary)
    }
}

/**
 * Which catalogue is on this TV, and how it is kept current.
 *
 * The buttons stay enabled while a check runs rather than greying out: a disabled control cannot hold
 * focus, and focus jumping away mid-check is worse on a remote than a message saying a check is
 * already running.
 */
@Composable
private fun CatalogueSection(
    state: CatalogueSettingsState,
    torrentAvailable: Boolean,
    message: SettingsMessage?,
    onToggleUpdates: (Boolean) -> Unit,
    onCheckNow: () -> Unit,
    onUseBundled: () -> Unit,
) {
    SettingsSection("Catalogue") {
        Text(
            text = catalogueSummary(state),
            style = MaterialTheme.typography.bodyMedium,
            color = TorfilxColors.TextPrimary,
        )
        when {
            !state.publisherConfigured -> Text(
                text = "This build trusts no catalogue publisher, so it keeps the catalogue it came with.",
                style = MaterialTheme.typography.labelMedium,
                color = TorfilxColors.TextTertiary,
            )
            !torrentAvailable -> Text(
                text = "BitTorrent is not available on this device, so the catalogue cannot be updated.",
                style = MaterialTheme.typography.labelMedium,
                color = TorfilxColors.TextTertiary,
            )
            else -> {
                SettingsToggleRow(
                    label = "Update the catalogue over the peer network",
                    description = "Looks for a newer catalogue signed by its publisher, and shares the " +
                        "one you have. Needs sharing on. No server is involved.",
                    checked = state.updatesEnabled,
                    onToggle = onToggleUpdates,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton(text = "Check for a new catalogue", onClick = onCheckNow, primary = false)
                    if (state.inUse.origin == CatalogueOrigin.FETCHED) {
                        TvButton(text = "Use the built-in catalogue", onClick = onUseBundled, primary = false)
                    }
                }
                Text(
                    text = catalogueStatus(state),
                    style = MaterialTheme.typography.labelMedium,
                    color = TorfilxColors.TextSecondary,
                )
            }
        }
        MessageLine(message, MessageSection.CATALOGUE)
    }
}

private fun catalogueSummary(state: CatalogueSettingsState): String {
    val inUse = state.inUse
    val titles = if (inUse.titleCount == 1) "1 title" else "${inUse.titleCount} titles"
    return when (inUse.origin) {
        CatalogueOrigin.FETCHED -> buildString {
            append("Catalogue ").append(inUse.version).append(" · ").append(titles)
            inUse.installedAtMs?.let { append(" · installed ").append(formatDateTime(it)) }
        }
        CatalogueOrigin.BUNDLED ->
            if (inUse.version > 0) "Built-in catalogue ${inUse.version} · $titles" else "Built-in catalogue · $titles"
    }
}

private fun catalogueStatus(state: CatalogueSettingsState): String = when (val update = state.update) {
    CatalogUpdateState.Idle -> when {
        !state.updatesEnabled -> "Automatic updates are off."
        else -> state.record.lastCheckMs?.let { "Last checked ${formatDateTime(it)}." }
            ?: "Checks start once sharing is on and the peer network is running."
    }
    is CatalogUpdateState.Checking -> "Looking for a newer catalogue…"
    is CatalogUpdateState.Downloading ->
        "Downloading catalogue ${update.version}… ${(update.progress * PERCENT).toInt()}%"
    is CatalogUpdateState.UpToDate -> "Up to date · checked ${formatDateTime(update.checkedAtMs)}."
    is CatalogUpdateState.Updated -> "Updated to catalogue ${update.version} · ${update.titleCount} titles."
    is CatalogUpdateState.Failed -> "${update.reason.message} · ${formatDateTime(update.atMs)}."
}

private const val PERCENT = 100

/** Stored fractions are floats; a chip counts as selected within this distance. */
private const val FRACTION_TOLERANCE = 0.01f

private fun formatDateTime(epochMs: Long): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(epochMs))

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = TorfilxColors.TextPrimary,
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
        }
    }
}

/** A status line from the view model, shown only under the section whose button produced it. */
@Composable
private fun MessageLine(message: SettingsMessage?, section: MessageSection) {
    if (message == null || message.section != section) return
    Text(
        text = message.text,
        style = MaterialTheme.typography.labelLarge,
        color = TorfilxColors.TextSecondary,
    )
}

/**
 * A label, an optional line of explanation, and one chip per option underneath.
 *
 * The chips sit on their own scrolling row rather than beside the label: some settings have five
 * choices, and the list narrows when the on-screen keyboard is open.
 */
@Composable
private fun <T> ChoiceRow(
    label: String,
    options: List<T>,
    selected: T?,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    description: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingLabel(label, description)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(options.size) { index ->
                val option = options[index]
                TvChip(
                    text = optionLabel(option),
                    selected = option == selected,
                    onClick = { onSelect(option) },
                )
            }
        }
    }
}

/**
 * A preferred language: "none" plus the common languages as chips, and "Other…" to type any code.
 *
 * A code typed earlier that is not in the common list gets a chip of its own, so the current choice is
 * always visible and selected.
 */
@Composable
private fun LanguageRow(
    label: String,
    noneLabel: String,
    current: String?,
    onSelect: (String?) -> Unit,
    onOther: () -> Unit,
) {
    val custom = current?.takeIf { code -> COMMON_LANGUAGES.none { it.first == code } }
    val options: List<String?> = listOf<String?>(null) + COMMON_LANGUAGES.map { it.first } + listOfNotNull(custom)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingLabel(label, description = "Now: ${current?.let(::languageName) ?: noneLabel}")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(options.size) { index ->
                val code = options[index]
                TvChip(
                    text = code?.let(::shortLanguageName) ?: noneLabel,
                    selected = code == current,
                    onClick = { onSelect(code) },
                )
            }
            item(key = "other") {
                TvChip(text = "Other…", selected = false, onClick = onOther)
            }
        }
    }
}

private fun shortLanguageName(code: String): String =
    COMMON_LANGUAGES.firstOrNull { it.first == code }?.second ?: code

private fun languageName(code: String): String =
    COMMON_LANGUAGES.firstOrNull { it.first == code }?.let { (c, name) -> "$name ($c)" } ?: code

@Composable
private fun SettingLabel(label: String, description: String?) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TorfilxColors.TextPrimary,
        )
        description?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = TorfilxColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    description: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            SettingLabel(label, description)
        }
        TvChip(
            text = if (checked) "On" else "Off",
            selected = checked,
            onClick = { onToggle(!checked) },
        )
    }
}

/**
 * A button for something that cannot be undone: the first press arms it and changes its text, the
 * second press within [CONFIRM_WINDOW_MS] does it. A remote has no hover and an OK press is easy to
 * make by accident, so a dialog-free second press is the cheapest guard that still works.
 */
@Composable
private fun ConfirmButton(text: String, confirmText: String, onConfirm: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(CONFIRM_WINDOW_MS)
            armed = false
        }
    }
    TvButton(
        text = if (armed) confirmText else text,
        onClick = {
            if (armed) {
                armed = false
                onConfirm()
            } else {
                armed = true
            }
        },
        primary = false,
    )
}

private fun QualityPreference.label(): String = when (this) {
    QualityPreference.AUTO -> "Auto"
    QualityPreference.DIRECT_ONLY -> "Direct only"
    QualityPreference.CAP_1080P -> "Max 1080p"
}

private fun LibrarySort.label(): String = when (this) {
    LibrarySort.RECENTLY_ADDED -> "Recently added"
    LibrarySort.ALPHABETICAL -> "A–Z"
    LibrarySort.YEAR -> "Year"
    LibrarySort.RATING -> "Rating"
}
