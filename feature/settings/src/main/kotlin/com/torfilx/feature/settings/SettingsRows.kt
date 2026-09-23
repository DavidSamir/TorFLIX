package com.torfilx.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.torfilx.core.ui.component.TvChip
import com.torfilx.core.ui.theme.TorfilxColors
import kotlinx.coroutines.delay

/*
 * The rows every settings pane is built from.
 *
 * Every control except a chip spans the pane's full width. That is what makes the D-pad predictable:
 * Up and Down always reach the row above or below, because a full-width row lies in the path of
 * whatever was focused, and Left always goes back to the category list, because nothing else sits to
 * the left of a row. The old screen put On/Off switches at the far right and buttons at the far left,
 * so Down from a switch skipped a button to reach the next switch, and Left from a switch landed on
 * whatever happened to be nearest, scrolling the page.
 */

/** Horizontal inset of a row's content; labels, descriptions and chips all line up on it. */
internal val ROW_INSET = 16.dp

private val RowShape = RoundedCornerShape(8.dp)
private val PillShape = RoundedCornerShape(20.dp)

/** How long a destructive row waits for its second press before it forgets the first. */
private const val CONFIRM_WINDOW_MS = 4_000L

/** A full-width focusable row. OK runs [onClick]. */
@Composable
internal fun SettingsRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    content: @Composable RowScope.(focused: Boolean) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RowShape)
            .background(if (focused) TorfilxColors.SurfaceHigh else TorfilxColors.Transparent)
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) TorfilxColors.Focus else TorfilxColors.Transparent,
                shape = RowShape,
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            // `clickable` is the focus target and handles DPAD_CENTER; see TvButton for why there is
            // no separate `focusable()`.
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = ROW_INSET, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content(focused)
    }
}

/** An on/off setting: the whole row toggles, and its state is the pill at the end. */
@Composable
internal fun ToggleRow(
    label: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    description: String? = null,
    focusRequester: FocusRequester? = null,
) {
    val state = if (checked) "On" else "Off"
    SettingsRow(
        onClick = { onToggle(!checked) },
        focusRequester = focusRequester,
        modifier = Modifier.semantics {
            role = Role.Switch
            stateDescription = state
        },
    ) {
        SettingLabel(label, description, Modifier.weight(1f))
        ValuePill(text = state, on = checked)
    }
}

/** Something to do now. [trailing] names a current value, when there is one worth showing. */
@Composable
internal fun ActionRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    trailing: String? = null,
    focusRequester: FocusRequester? = null,
) {
    SettingsRow(
        onClick = onClick,
        focusRequester = focusRequester,
        modifier = modifier.semantics { role = Role.Button },
    ) {
        SettingLabel(label, description, Modifier.weight(1f))
        trailing?.let {
            Text(text = it, style = MaterialTheme.typography.labelLarge, color = TorfilxColors.TextSecondary)
        }
    }
}

/**
 * Something that cannot be undone: the first press arms it and changes its label, the second press
 * within [CONFIRM_WINDOW_MS] does it. A remote has no hover and an OK press is easy to make by
 * accident, so a dialog-free second press is the cheapest guard that still works.
 */
@Composable
internal fun ConfirmActionRow(
    label: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    description: String? = null,
    focusRequester: FocusRequester? = null,
) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(CONFIRM_WINDOW_MS)
            armed = false
        }
    }
    ActionRow(
        label = if (armed) confirmLabel else label,
        description = description,
        trailing = if (armed) "Press OK again" else null,
        focusRequester = focusRequester,
        onClick = {
            if (armed) {
                armed = false
                onConfirm()
            } else {
                armed = true
            }
        },
    )
}

/**
 * A label, an optional line of explanation, and one chip per option.
 *
 * The chips wrap onto further lines rather than scrolling sideways, so every choice is on screen and
 * Down from any chip reaches the chip below it or the next row. Arriving from another row lands on the
 * selected chip; [entryFocusRequester] (the pane's way in, when this is its first control) does too.
 */
@Composable
internal fun <T> ChoiceRow(
    label: String,
    options: List<T>,
    selected: T?,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    description: String? = null,
    entryFocusRequester: FocusRequester? = null,
) {
    val entryIndex = options.indexOf(selected).coerceAtLeast(0)
    ChipGroup(label = label, description = description) { entry ->
        options.forEachIndexed { index, option ->
            TvChip(
                text = optionLabel(option),
                selected = option == selected,
                onClick = { onSelect(option) },
                modifier = if (index == entryIndex) Modifier.entryChip(entry, entryFocusRequester) else Modifier,
            )
        }
    }
}

/**
 * The label and the wrapping chips of a choice. [chips] receives the requester to put on the chip that
 * the D-pad should enter on: the selected one.
 */
@Composable
internal fun ChipGroup(label: String, description: String?, chips: @Composable (entry: FocusRequester) -> Unit) {
    val entry = remember { FocusRequester() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ROW_INSET, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SettingLabel(label, description)
        FlowRow(
            modifier = Modifier.dpadEntersAt(entry),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            chips(entry)
        }
    }
}

/**
 * Makes this a focus group that the D-pad enters at [target].
 *
 * Not `focusRestorer`: that redirects every entry, including a request for one particular control,
 * so asking for the "Other…" chip after the keyboard closed landed on the group's first chip instead.
 * Here only a D-pad move into the group is redirected; a focus request made in code goes where it asked.
 */
internal fun Modifier.dpadEntersAt(target: FocusRequester): Modifier =
    focusProperties {
        onEnter = {
            if (requestedFocusDirection != FocusDirection.Enter) {
                // Not attached (a pane without that control): the ordinary search decides instead.
                runCatching { target.requestFocus() }
            }
        }
    }.focusGroup()

/** The group's entry chip, which is also the pane's first control when [paneEntry] is given. */
internal fun Modifier.entryChip(groupEntry: FocusRequester, paneEntry: FocusRequester?): Modifier =
    focusRequester(groupEntry).then(if (paneEntry != null) Modifier.focusRequester(paneEntry) else Modifier)

@Composable
internal fun SettingLabel(label: String, description: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
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
private fun ValuePill(text: String, on: Boolean) {
    Box(
        modifier = Modifier
            .clip(PillShape)
            .background(if (on) TorfilxColors.Accent else TorfilxColors.SurfaceHighest)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (on) TorfilxColors.TextOnAccent else TorfilxColors.TextSecondary,
        )
    }
}

/** A paragraph of explanation, aligned with the rows' labels. */
@Composable
internal fun InfoText(text: String, color: androidx.compose.ui.graphics.Color = TorfilxColors.TextSecondary) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier.padding(horizontal = ROW_INSET, vertical = 4.dp),
    )
}

/** A heading inside a pane, for a group of rows that belong together. */
@Composable
internal fun SubHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = TorfilxColors.TextPrimary,
        modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, top = 16.dp, bottom = 4.dp),
    )
}

/** A status line from the view model, shown only in the pane whose row produced it. */
@Composable
internal fun MessageLine(message: SettingsMessage?, section: MessageSection) {
    if (message == null || message.section != section) return
    Text(
        text = message.text,
        style = MaterialTheme.typography.labelLarge,
        color = TorfilxColors.TextPrimary,
        modifier = Modifier.padding(horizontal = ROW_INSET, vertical = 8.dp),
    )
}

/** A label and a value, for the read-only tables in About and Sharing. */
@Composable
internal fun InfoLine(label: String, value: String) {
    Row(Modifier.padding(horizontal = ROW_INSET)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TorfilxColors.TextSecondary,
            modifier = Modifier.width(180.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = TorfilxColors.TextPrimary)
    }
}
