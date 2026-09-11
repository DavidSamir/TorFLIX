package com.torfilx.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
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
import com.torfilx.core.model.MetadataTimeout
import com.torfilx.core.model.QualityPreference
import com.torfilx.core.model.StreamingMode
import com.torfilx.core.ui.component.KeyboardLayout
import com.torfilx.core.ui.component.OnScreenKeyboard
import com.torfilx.core.ui.component.SearchField
import com.torfilx.core.ui.component.formatBytes
import com.torfilx.core.ui.component.TvButton
import com.torfilx.core.ui.component.TvChip
import com.torfilx.core.ui.theme.LocalTorfilxDimens
import com.torfilx.core.ui.theme.TorfilxColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Which text field the on-screen keyboard is currently editing. */
private enum class EditingField { NONE, AUDIO_LANGUAGE, SUBTITLE_LANGUAGE }

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
            verticalArrangement = Arrangement.spacedBy(14.dp),
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
                        checked = state.settings.autoplayNextEpisode,
                        onToggle = viewModel::setAutoplayNext,
                    )
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
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        QualityPreference.entries.forEach { preference ->
                            TvChip(
                                text = preference.label(),
                                selected = state.settings.quality == preference,
                                onClick = { viewModel.setQuality(preference) },
                            )
                        }
                    }
                }
            }

            item(key = "languages") {
                SettingsSection("Language") {
                    SettingsValueRow(
                        label = "Preferred audio language",
                        value = state.settings.preferredAudioLanguage ?: "Original",
                        onClick = {
                            startEditing(
                                EditingField.AUDIO_LANGUAGE,
                                state.settings.preferredAudioLanguage.orEmpty(),
                            )
                        },
                    )
                    SettingsValueRow(
                        label = "Preferred subtitle language",
                        value = state.settings.preferredSubtitleLanguage ?: "None",
                        onClick = {
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
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TvChip(
                                text = "Disk share: ${(state.storageFraction * 100).toInt()}% of free",
                                selected = true,
                                onClick = viewModel::cycleStorageFraction,
                            )
                        }
                        Text(
                            text = with(state.sharingStats) {
                                "Using ${formatBytes(diskUsedBytes)} of ${formatBytes(diskCapBytes)} " +
                                    "(${formatBytes(freeSpaceBytes)} free) · " +
                                    "up ${formatBytes(totalUploadedBytes)} · " +
                                    "down ${formatBytes(totalDownloadedBytes)}"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = TorfilxColors.TextSecondary,
                        )
                    }
                }
            }

            item(key = "catalogue") {
                CatalogueSection(
                    state = state.catalogue,
                    torrentAvailable = state.torrentAvailable,
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
                    SettingsChoiceRow(label = "Time to find a title") {
                        MetadataTimeout.entries.forEach { option ->
                            TvChip(
                                text = option.label,
                                selected = state.settings.metadataTimeout == option,
                                onClick = { viewModel.setMetadataTimeout(option) },
                            )
                        }
                    }
                    SettingsChoiceRow(label = "Streaming mode") {
                        StreamingMode.entries.forEach { mode ->
                            TvChip(
                                text = mode.label,
                                selected = state.settings.streamingMode == mode,
                                onClick = { viewModel.setStreamingMode(mode) },
                            )
                        }
                    }
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
                    }
                }
            }

            item(key = "maintenance") {
                SettingsSection("Maintenance") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvButton(
                            text = "Clear downloaded data",
                            onClick = viewModel::clearDownloadedData,
                            primary = false,
                        )
                        TvButton(
                            text = "Clear search history",
                            onClick = viewModel::clearSearchHistory,
                            primary = false,
                        )
                        TvButton(text = "Export logs", onClick = viewModel::exportLogs, primary = false)
                    }
                    state.message?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.labelLarge,
                            color = TorfilxColors.TextSecondary,
                        )
                    }
                }
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

private fun formatDateTime(epochMs: Long): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(epochMs))

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = TorfilxColors.TextPrimary,
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            content()
        }
    }
}

@Composable
private fun SettingsValueRow(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = TorfilxColors.TextPrimary,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = TorfilxColors.TextSecondary,
            )
        }
        TvButton(text = "Change", onClick = onClick, primary = false)
    }
}

/** A labelled row whose right side is a set of choice chips supplied by the caller. */
@Composable
private fun SettingsChoiceRow(label: String, chips: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TorfilxColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { chips() }
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
        TvChip(
            text = if (checked) "On" else "Off",
            selected = checked,
            onClick = { onToggle(!checked) },
        )
    }
}

private fun QualityPreference.label(): String = when (this) {
    QualityPreference.AUTO -> "Quality: Auto"
    QualityPreference.DIRECT_ONLY -> "Quality: Direct only"
    QualityPreference.CAP_1080P -> "Quality: Max 1080p"
}
