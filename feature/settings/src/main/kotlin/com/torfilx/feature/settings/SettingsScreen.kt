package com.torfilx.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.torfilx.core.ui.component.CapsText
import com.torfilx.core.ui.theme.LocalTorfilxDimens
import com.torfilx.core.ui.theme.TorfilxColors
import com.torfilx.core.ui.theme.TorfilxMotion
import com.torfilx.core.ui.theme.TorfilxType
import com.torfilx.core.ui.theme.animateFocus
import com.torfilx.core.ui.theme.focusRowMark
import com.torfilx.core.ui.theme.ruleBelow

/** The settings, one category at a time. Sharing and Catalogue come first: nothing plays without them. */
internal enum class SettingsCategory(val label: String, val summary: String) {
    SHARING("Sharing", "Uploading while you watch, upload speed and disk space."),
    CATALOGUE("Catalogue", "Which titles this TV has, and keeping them current."),
    PLAYBACK("Playback", "Autoplay, quality, seeking and the picture."),
    LANGUAGE("Subtitles and language", "Preferred audio and subtitle languages, and how subtitles look."),
    LIBRARY("Home and library", "How Movies and Shows are sorted, and motion on Home."),
    ENGINE("Streaming engine", "Peer discovery and decoding, for when a title will not start or stutters."),
    WATCH_DATA("Watch data", "Continue Watching and My List: back up, restore or clear."),
    ABOUT("About", "This build and device, logs, and resetting settings."),
}

private val RAIL_WIDTH = 250.dp

/**
 * Settings as a category list on the left and that category's settings on the right.
 *
 * Moving through the list shows each category at once; Right or OK goes into it, Left or Back comes
 * back out. Entering the screen from the tab bar lands on the category last shown, entering a pane
 * lands on its first control, and entering a set of chips lands on the selected one.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val dimens = LocalTorfilxDimens.current

    var selected by rememberSaveable { mutableStateOf(SettingsCategory.SHARING) }
    var editing by remember { mutableStateOf<LanguageField?>(null) }
    var paneHasFocus by remember { mutableStateOf(false) }
    val railItems = remember { SettingsCategory.entries.associateWith { FocusRequester() } }
    val paneFirst = remember { SettingsCategory.entries.associateWith { FocusRequester() } }
    val selectedRailItem = railItems.getValue(selected)

    // Back closes the keyboard first, then leaves the pane for the list, then leaves the screen.
    BackHandler(enabled = true) {
        when {
            editing != null -> editing = null
            paneHasFocus -> runCatching { selectedRailItem.requestFocus() }
            else -> onBack()
        }
    }

    Box(modifier = modifier.fillMaxSize().background(TorfilxColors.Background)) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = dimens.overscanHorizontal,
                    end = dimens.overscanHorizontal,
                    top = 12.dp,
                    bottom = dimens.overscanVertical,
                )
                .dpadEntersAt(selectedRailItem),
            horizontalArrangement = Arrangement.spacedBy(40.dp),
        ) {
            CategoryRail(
                selected = selected,
                sharingOn = state.sharingConsent,
                railItems = railItems,
                onSelect = { category ->
                    editing = null
                    selected = category
                },
                onOpen = { category -> runCatching { paneFirst.getValue(category).requestFocus() } },
            )

            // A new pane per category, so each opens scrolled to the top and enters on its first control.
            key(selected) {
                val first = paneFirst.getValue(selected)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .onFocusChanged { paneHasFocus = it.hasFocus }
                        .dpadEntersAt(first)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PaneHeader(selected)
                    when (selected) {
                        SettingsCategory.SHARING -> SharingPane(state, viewModel, first)
                        SettingsCategory.CATALOGUE -> CataloguePane(state, viewModel, first)
                        SettingsCategory.PLAYBACK -> PlaybackPane(state, viewModel, first)
                        SettingsCategory.LANGUAGE -> LanguagePane(state, viewModel, first, editing) { editing = it }
                        SettingsCategory.LIBRARY -> LibraryPane(state, viewModel, first)
                        SettingsCategory.ENGINE -> EnginePane(state, viewModel, first)
                        SettingsCategory.WATCH_DATA -> WatchDataPane(state, viewModel, first)
                        SettingsCategory.ABOUT -> AboutPane(state, viewModel, first)
                    }
                }
            }
        }

        editing?.let { field ->
            LanguageEditor(
                field = field,
                initial = when (field) {
                    LanguageField.AUDIO -> state.settings.preferredAudioLanguage
                    LanguageField.SUBTITLE -> state.settings.preferredSubtitleLanguage
                }.orEmpty(),
                onSave = { code ->
                    when (field) {
                        LanguageField.AUDIO -> viewModel.setAudioLanguage(code)
                        LanguageField.SUBTITLE -> viewModel.setSubtitleLanguage(code)
                    }
                    editing = null
                },
                onCancel = { editing = null },
            )
        }
    }
}

/** The categories. Focusing one shows it ([onSelect]); OK moves into its pane ([onOpen]). */
@Composable
private fun CategoryRail(
    selected: SettingsCategory,
    sharingOn: Boolean,
    railItems: Map<SettingsCategory, FocusRequester>,
    onSelect: (SettingsCategory) -> Unit,
    onOpen: (SettingsCategory) -> Unit,
) {
    Column(
        modifier = Modifier
            .width(RAIL_WIDTH)
            .fillMaxHeight()
            .dpadEntersAt(railItems.getValue(selected)),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SettingsCategory.entries.forEach { category ->
            CategoryItem(
                category = category,
                selected = category == selected,
                trailing = when (category) {
                    SettingsCategory.SHARING -> if (sharingOn) "On" else "Off"
                    else -> null
                },
                focusRequester = railItems.getValue(category),
                onFocused = { if (category != selected) onSelect(category) },
                onClick = { onOpen(category) },
            )
        }
    }
}

/** The pane's title in the serif, its summary as an italic line, and a rule under both. */
@Composable
private fun PaneHeader(category: SettingsCategory) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .ruleBelow(LocalTorfilxDimens.current.hairline)
            .padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = category.label,
            style = TorfilxType.SectionTitle,
            color = TorfilxColors.TextPrimary,
        )
        Text(
            text = category.summary,
            style = TorfilxType.Caption,
            color = TorfilxColors.TextSecondary,
        )
    }
}

/**
 * One category in the list, set like a line in a table of contents. Focusing it shows the category;
 * OK goes into it.
 *
 * Focus marks the line with a wash and a rule down its edge, like an episode row. The selected
 * category stays in ivory with a thin rule at its edge while focus is in its pane, so it is always
 * clear which category the pane belongs to.
 */
@Composable
private fun CategoryItem(
    category: SettingsCategory,
    selected: Boolean,
    trailing: String?,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val focus = animateFocus(focused, durationMs = TorfilxMotion.FOCUS_MS, label = "categoryFocus")
    LaunchedEffect(focused) { if (focused) onFocused() }
    val dimens = LocalTorfilxDimens.current

    val labelColor = when {
        focused || selected -> TorfilxColors.TextPrimary
        else -> TorfilxColors.TextTertiary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (selected && !focused) Modifier.ruleAtStart(dimens.hairline) else Modifier)
            .focusRowMark(focus, dimens.underline)
            .focusRequester(focusRequester)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .semantics {
                this.selected = selected
                role = Role.Tab
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = category.label,
            style = MaterialTheme.typography.bodyMedium,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing?.let {
            CapsText(text = it, style = TorfilxType.MetaCaps, color = TorfilxColors.TextTertiary)
        }
    }
}

/** A hairline down the left edge: the chosen category, while focus is in its pane. */
private fun Modifier.ruleAtStart(thickness: Dp): Modifier = drawBehind {
    drawRect(TorfilxColors.TextTertiary, size = Size(thickness.toPx().coerceAtLeast(1f), size.height))
}
