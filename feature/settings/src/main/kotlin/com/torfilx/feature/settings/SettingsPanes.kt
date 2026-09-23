package com.torfilx.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
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
import com.torfilx.core.ui.theme.TorfilxColors
import com.torfilx.core.ui.util.Format
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/*
 * One composable per settings category. Each takes `first`, the focus requester the screen sends focus
 * to when the pane is entered for the first time, and attaches it to its first focusable control.
 */

// --- Sharing ------------------------------------------------------------------------------------

@Composable
internal fun SharingPane(state: SettingsUiState, viewModel: SettingsViewModel, first: FocusRequester) {
    if (!state.torrentAvailable) {
        InfoText("BitTorrent is not available on this device.", TorfilxColors.TextTertiary)
        return
    }
    ToggleRow(
        label = "Share while watching",
        description = "Every title plays over BitTorrent, so this is what makes playback and catalogue " +
            "updates work. Streaming also uploads the title to others, and your IP address is visible " +
            "to everyone sharing it.",
        checked = state.sharingConsent,
        onToggle = viewModel::setSharingConsent,
        focusRequester = first,
    )
    ToggleRow(
        label = "Keep seeding after playback",
        description = "Keeps sharing what is already on disk until the space is needed.",
        checked = state.seedingEnabled,
        onToggle = viewModel::setSeedingEnabled,
    )
    ChoiceRow(
        label = "Upload speed limit",
        description = "How fast this TV may upload while sharing. 2 MB/s is the built-in limit and the " +
            "lowest choice; you can only give more. Applies at once.",
        options = UploadLimit.entries,
        selected = state.settings.uploadLimit,
        optionLabel = { it.label },
        onSelect = viewModel::setUploadLimit,
    )
    ChoiceRow(
        label = "Disk space for shared titles",
        description = with(state.sharingStats) {
            "A share of the free space; 500 MB is always left free. Using ${formatBytes(diskUsedBytes)} " +
                "of ${formatBytes(diskCapBytes)} (${formatBytes(freeSpaceBytes)} free)."
        },
        options = SettingsViewModel.STORAGE_CHOICES,
        selected = SettingsViewModel.STORAGE_CHOICES.firstOrNull { abs(it - state.storageFraction) < FRACTION_TOLERANCE },
        optionLabel = { "${(it * PERCENT).toInt()}% of free" },
        onSelect = viewModel::setStorageFraction,
    )
    ConfirmActionRow(
        label = "Clear downloaded data",
        confirmLabel = "Press again to clear downloaded data",
        description = "Deletes what is cached for playback and seeding. Watch progress and My List are kept.",
        onConfirm = viewModel::clearDownloadedData,
    )
    MessageLine(state.message, MessageSection.SHARING)
    StreamedTable(
        totals = state.streamed,
        downloadRate = state.sharingStats.downloadRateBytesPerSecond,
        uploadRate = state.sharingStats.uploadRateBytesPerSecond,
        active = state.sharingStats.activeTorrents > 0,
    )
}

/** How much this TV has streamed in from peers and out to them. Text only; it sits last in its pane. */
@Composable
private fun StreamedTable(totals: StreamedTotals, downloadRate: Int, uploadRate: Int, active: Boolean) {
    SubHeading("Data streamed")
    InfoText(
        "In is what this TV downloaded from peers to play; out is what it uploaded to others. Counted " +
            "since downloaded data was last cleared, and updated every 30 seconds while streaming.",
    )
    Column(
        modifier = Modifier.padding(horizontal = ROW_INSET, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        StreamedRow(label = "", inbound = "In", outbound = "Out", header = true)
        StreamedRow("Today", totals.today)
        StreamedRow("Last 30 days", totals.last30Days)
        StreamedRow("All time", totals.allTime)
        if (active) {
            StreamedRow(label = "Right now", inbound = Format.speed(downloadRate), outbound = Format.speed(uploadRate))
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

// --- Catalogue ----------------------------------------------------------------------------------

/**
 * Which catalogue is on this TV, and how it is kept current.
 *
 * The check row stays focusable while a check runs rather than greying out: a disabled control cannot
 * hold focus, and focus jumping away mid-check is worse on a remote than a message saying a check is
 * already running. The live status of the check is the row's own description.
 */
@Composable
internal fun CataloguePane(state: SettingsUiState, viewModel: SettingsViewModel, first: FocusRequester) {
    val catalogue = state.catalogue
    InfoText(catalogueSummary(catalogue), TorfilxColors.TextPrimary)
    when {
        !catalogue.publisherConfigured -> InfoText(
            "This build trusts no catalogue publisher, so it keeps the catalogue it came with.",
            TorfilxColors.TextTertiary,
        )
        !state.torrentAvailable -> InfoText(
            "BitTorrent is not available on this device, so the catalogue cannot be updated.",
            TorfilxColors.TextTertiary,
        )
        else -> {
            // Both rows around the check remove themselves once pressed. Each hands focus to the check
            // first, so focus is never left on a row that no longer exists.
            val checkRow = remember { FocusRequester() }
            val needsSharing = !state.sharingConsent
            if (needsSharing) {
                ActionRow(
                    label = "Turn on sharing",
                    description = "New catalogues arrive over the peer network, so they need sharing on. " +
                        "Sharing also uploads what you watch to others, who can see your IP address.",
                    onClick = {
                        runCatching { checkRow.requestFocus() }
                        viewModel.setSharingConsent(true)
                    },
                    focusRequester = first,
                )
            }
            ActionRow(
                label = "Check for a new catalogue",
                description = catalogueStatus(catalogue),
                onClick = viewModel::checkCatalogueNow,
                modifier = Modifier.focusRequester(checkRow),
                focusRequester = if (needsSharing) null else first,
            )
            if (catalogue.inUse.origin == CatalogueOrigin.FETCHED) {
                ActionRow(
                    label = "Use the built-in catalogue",
                    description = "Goes back to the catalogue this app came with. The one you leave is not " +
                        "downloaded again; a newer one still is.",
                    onClick = {
                        runCatching { checkRow.requestFocus() }
                        viewModel.useBundledCatalogue()
                    },
                )
            }
            ToggleRow(
                label = "Update the catalogue over the peer network",
                description = "Looks for a newer catalogue signed by its publisher, and shares the one you " +
                    "have. No server is involved.",
                checked = catalogue.updatesEnabled,
                onToggle = viewModel::setCatalogUpdatesEnabled,
            )
        }
    }
    MessageLine(state.message, MessageSection.CATALOGUE)
}

internal fun catalogueSummary(state: CatalogueSettingsState): String {
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
            ?: "Not checked yet. Checks start once sharing is on and the peer network is running."
    }
    is CatalogUpdateState.Checking -> "Looking for a newer catalogue…"
    is CatalogUpdateState.Downloading ->
        "Downloading catalogue ${update.version}… ${(update.progress * PERCENT).toInt()}%"
    is CatalogUpdateState.UpToDate -> "Up to date · checked ${formatDateTime(update.checkedAtMs)}."
    is CatalogUpdateState.Updated -> "Updated to catalogue ${update.version} · ${update.titleCount} titles."
    is CatalogUpdateState.Failed -> "${update.reason.message} · ${formatDateTime(update.atMs)}."
}

// --- Playback -----------------------------------------------------------------------------------

@Composable
internal fun PlaybackPane(state: SettingsUiState, viewModel: SettingsViewModel, first: FocusRequester) {
    val settings = state.settings
    ToggleRow(
        label = "Autoplay next episode",
        description = "Also starts finding peers for the next episode while this one ends.",
        checked = settings.autoplayNextEpisode,
        onToggle = viewModel::setAutoplayNext,
        focusRequester = first,
    )
    if (settings.autoplayNextEpisode) {
        ChoiceRow(
            label = "Countdown before the next episode",
            options = AutoplayCountdown.entries,
            selected = settings.autoplayCountdown,
            optionLabel = { it.label },
            onSelect = viewModel::setAutoplayCountdown,
        )
    }
    ToggleRow(
        label = "Skip intros automatically",
        checked = settings.skipIntroAutomatically,
        onToggle = viewModel::setSkipIntroAutomatically,
    )
    ChoiceRow(
        label = "Quality",
        options = QualityPreference.entries,
        selected = settings.quality,
        optionLabel = { it.label() },
        onSelect = viewModel::setQuality,
    )
    ChoiceRow(
        label = "Skip with ← and →",
        description = "Rewind and fast-forward on the remote jump three times as far.",
        options = SeekStep.entries,
        selected = settings.seekStep,
        optionLabel = { it.label },
        onSelect = viewModel::setSeekStep,
    )
    ChoiceRow(
        label = "Picture size for new titles",
        description = "Fit shows the whole picture, Fill stretches it, Zoom crops the edges. Changing it " +
            "in the player lasts until you leave.",
        options = AspectPreference.entries,
        selected = settings.defaultAspect,
        optionLabel = { it.label },
        onSelect = viewModel::setDefaultAspect,
    )
    ToggleRow(
        label = "Match display frame rate",
        description = "Switches the TV to 24/50/60 Hz to stop film judder.",
        checked = settings.frameRateMatching,
        onToggle = viewModel::setFrameRateMatching,
    )
    ToggleRow(
        label = "Tunneled playback",
        description = "Recommended for 4K/HDR. Turn off if your AV receiver glitches.",
        checked = settings.tunneledPlayback,
        onToggle = viewModel::setTunneledPlayback,
    )
    ToggleRow(
        label = "Show stream stats while playing",
        description = "Speed, peers and download progress in the corner of the screen.",
        checked = settings.showStreamStats,
        onToggle = viewModel::setShowStreamStats,
    )
}

// --- Subtitles and language ---------------------------------------------------------------------

/** Which language the on-screen keyboard is editing. */
internal enum class LanguageField { AUDIO, SUBTITLE }

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

/**
 * The language rows. "Other…" opens the on-screen keyboard over the screen (see [LanguageEditor]);
 * when it closes, focus goes back to the "Other…" chip that opened it.
 */
@Composable
internal fun LanguagePane(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    first: FocusRequester,
    editing: LanguageField?,
    onEdit: (LanguageField?) -> Unit,
) {
    val audioOther = remember { FocusRequester() }
    val subtitleOther = remember { FocusRequester() }
    var lastEdited by remember { mutableStateOf<LanguageField?>(null) }
    LaunchedEffect(editing) {
        if (editing == null) {
            when (lastEdited) {
                LanguageField.AUDIO -> runCatching { audioOther.requestFocus() }
                LanguageField.SUBTITLE -> runCatching { subtitleOther.requestFocus() }
                null -> Unit
            }
        }
        lastEdited = editing
    }

    val settings = state.settings
    LanguageRow(
        label = "Preferred audio language",
        noneLabel = "Original",
        current = settings.preferredAudioLanguage,
        onSelect = viewModel::setAudioLanguage,
        onOther = { onEdit(LanguageField.AUDIO) },
        firstChipFocusRequester = first,
        otherFocusRequester = audioOther,
    )
    LanguageRow(
        label = "Preferred subtitle language",
        noneLabel = "None",
        current = settings.preferredSubtitleLanguage,
        onSelect = viewModel::setSubtitleLanguage,
        onOther = { onEdit(LanguageField.SUBTITLE) },
        otherFocusRequester = subtitleOther,
    )
    ToggleRow(
        label = "Subtitles on by default",
        checked = settings.subtitlesEnabledByDefault,
        onToggle = viewModel::setSubtitlesEnabled,
    )
    ChoiceRow(
        label = "Subtitle size",
        description = "TV default follows the TV's own caption settings.",
        options = SubtitleSize.entries,
        selected = settings.subtitleSize,
        optionLabel = { it.label },
        onSelect = viewModel::setSubtitleSize,
    )
    ChoiceRow(
        label = "Subtitle style",
        options = SubtitleStyle.entries,
        selected = settings.subtitleStyle,
        optionLabel = { it.label },
        onSelect = viewModel::setSubtitleStyle,
    )
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
    otherFocusRequester: FocusRequester,
    firstChipFocusRequester: FocusRequester? = null,
) {
    val custom = current?.takeIf { code -> COMMON_LANGUAGES.none { it.first == code } }
    val options: List<String?> = listOf<String?>(null) + COMMON_LANGUAGES.map { it.first } + listOfNotNull(custom)
    val entryIndex = options.indexOf(current).coerceAtLeast(0)
    ChipGroup(label = label, description = "Now: ${current?.let(::languageName) ?: noneLabel}") { entry ->
        options.forEachIndexed { index, code ->
            TvChip(
                text = code?.let(::shortLanguageName) ?: noneLabel,
                selected = code == current,
                onClick = { onSelect(code) },
                modifier = if (index == entryIndex) Modifier.entryChip(entry, firstChipFocusRequester) else Modifier,
            )
        }
        TvChip(
            text = "Other…",
            selected = false,
            onClick = onOther,
            modifier = Modifier.focusRequester(otherFocusRequester),
        )
    }
}

/**
 * Typing a language code, over the whole screen.
 *
 * An overlay rather than a swap of the pane's rows: the "Other…" chip that opened it stays where it
 * was, so focus has somewhere to come back to. Focus is held inside until Save, Cancel or Back, so a
 * stray Left cannot switch category and throw away what was typed.
 */
@Composable
internal fun LanguageEditor(
    field: LanguageField,
    initial: String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var buffer by remember(field) { mutableStateOf(initial) }
    var layout by remember(field) { mutableStateOf(KeyboardLayout.LATIN) }
    val firstKey = remember { FocusRequester() }
    LaunchedEffect(field) { runCatching { firstKey.requestFocus() } }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TorfilxColors.ScrimStrong)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(TorfilxColors.Surface)
                .padding(horizontal = 28.dp, vertical = 16.dp)
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Column(
                modifier = Modifier.width(300.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = when (field) {
                        LanguageField.AUDIO -> "Audio language code"
                        LanguageField.SUBTITLE -> "Subtitle language code"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = TorfilxColors.TextPrimary,
                )
                Text(
                    text = "Two letters, for example en or he.",
                    style = MaterialTheme.typography.labelMedium,
                    color = TorfilxColors.TextSecondary,
                )
                SearchField(value = buffer, placeholder = "Type a code")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton(text = "Save", onClick = { onSave(buffer) })
                    TvButton(text = "Cancel", onClick = onCancel, primary = false)
                }
            }
            // Scrolls when the TV's usable height is shorter than the keyboard; a focused key is
            // always brought into view.
            OnScreenKeyboard(
                onCharacter = { character -> buffer += character.lowercaseChar() },
                onBackspace = { buffer = buffer.dropLast(1) },
                onClear = { buffer = "" },
                layout = layout,
                onLayoutChange = { layout = it },
                firstKeyFocusRequester = firstKey,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        }
    }
}

private fun shortLanguageName(code: String): String =
    COMMON_LANGUAGES.firstOrNull { it.first == code }?.second ?: code

private fun languageName(code: String): String =
    COMMON_LANGUAGES.firstOrNull { it.first == code }?.let { (c, name) -> "$name ($c)" } ?: code

// --- Home and library ---------------------------------------------------------------------------

@Composable
internal fun LibraryPane(state: SettingsUiState, viewModel: SettingsViewModel, first: FocusRequester) {
    ChoiceRow(
        label = "Sort Movies and Shows by",
        options = LibrarySort.entries,
        selected = state.settings.librarySort,
        optionLabel = { it.label() },
        onSelect = viewModel::setLibrarySort,
        entryFocusRequester = first,
    )
    ToggleRow(
        label = "Hide watched titles",
        description = "Movies and Shows open on unwatched titles. My List still shows everything.",
        checked = state.settings.hideWatched,
        onToggle = viewModel::setHideWatched,
    )
    ToggleRow(
        label = "Reduce motion",
        description = "No banner auto-advance, no zoom on focus, no pulsing placeholders. Helps on slower sticks.",
        checked = state.settings.reduceMotion,
        onToggle = viewModel::setReduceMotion,
    )
    ActionRow(
        label = "Clear search history",
        description = "Forgets the searches listed under Search.",
        onClick = viewModel::clearSearchHistory,
    )
    MessageLine(state.message, MessageSection.LIBRARY)
}

// --- Streaming engine ---------------------------------------------------------------------------

@Composable
internal fun EnginePane(state: SettingsUiState, viewModel: SettingsViewModel, first: FocusRequester) {
    val settings = state.settings
    InfoText(
        "Defaults suit most devices. Change these if a title will not start or stutters; they take " +
            "effect the next time you press Play.",
    )
    ToggleRow(
        label = "Find peers with DHT",
        description = "The distributed peer network. Catalogue updates need it. Turn off only if your " +
            "network blocks it.",
        checked = settings.useDht,
        onToggle = viewModel::setUseDht,
        focusRequester = first,
    )
    ToggleRow(
        label = "Use extra public trackers",
        description = "Adds well-known trackers so titles find peers even when a magnet's own are dead.",
        checked = settings.useExtraTrackers,
        onToggle = viewModel::setUseExtraTrackers,
    )
    ToggleRow(
        label = "Force software video decoding",
        description = "Try this if video is black, glitchy, or refuses to play on this device.",
        checked = settings.forceSoftwareDecoder,
        onToggle = viewModel::setForceSoftwareDecoder,
    )
    ChoiceRow(
        label = "Time to find a title",
        options = MetadataTimeout.entries,
        selected = settings.metadataTimeout,
        optionLabel = { it.label },
        onSelect = viewModel::setMetadataTimeout,
    )
    ChoiceRow(
        label = "Streaming mode",
        options = StreamingMode.entries,
        selected = settings.streamingMode,
        optionLabel = { it.label },
        onSelect = viewModel::setStreamingMode,
    )
}

// --- Watch data ---------------------------------------------------------------------------------

@Composable
internal fun WatchDataPane(state: SettingsUiState, viewModel: SettingsViewModel, first: FocusRequester) {
    InfoText(
        "Continue Watching and My List live only on this device. Back them up and copy the file off to " +
            "keep them across a reinstall or a new stick.",
    )
    ActionRow(
        label = "Back up watch data",
        onClick = viewModel::backupUserData,
        focusRequester = first,
    )
    ActionRow(
        label = "Restore watch data",
        description = "Reads the backup file from this device.",
        onClick = viewModel::restoreUserData,
    )
    ConfirmActionRow(
        label = "Clear watch history",
        confirmLabel = "Press again to clear watch history",
        description = "Empties Continue Watching and forgets what you have watched. My List is kept.",
        onConfirm = viewModel::clearWatchHistory,
    )
    MessageLine(state.message, MessageSection.WATCH_DATA)
}

// --- About --------------------------------------------------------------------------------------

/** The installed build and device, then logs, then "Reset all settings" as the last control. */
@Composable
internal fun AboutPane(state: SettingsUiState, viewModel: SettingsViewModel, first: FocusRequester) {
    Column(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        InfoLine("App", "TORFILX ${state.about.appVersion}")
        InfoLine("Device", state.about.device)
        InfoLine("System", state.about.system)
        InfoLine("Catalogue", catalogueSummary(state.catalogue))
        InfoLine("BitTorrent", if (state.torrentAvailable) "Available" else "Not available on this device")
    }
    ActionRow(
        label = "Export logs",
        description = "Writes the log to a file you can pull with adb, for reporting a problem.",
        onClick = viewModel::exportLogs,
        focusRequester = first,
    )
    ConfirmActionRow(
        label = "Reset all settings",
        confirmLabel = "Press again to reset all settings",
        description = "Puts every setting back to its default. Your sharing choice, watch history and My " +
            "List are kept.",
        onConfirm = viewModel::resetSettings,
    )
    MessageLine(state.message, MessageSection.ABOUT)
}

// --- Shared -------------------------------------------------------------------------------------

private const val PERCENT = 100

/** Stored fractions are floats; a chip counts as selected within this distance. */
private const val FRACTION_TOLERANCE = 0.01f

private fun formatDateTime(epochMs: Long): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(epochMs))

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
