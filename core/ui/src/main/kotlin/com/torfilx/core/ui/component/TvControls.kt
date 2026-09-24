package com.torfilx.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.core.ui.theme.LocalTorfilxDimens
import com.torfilx.core.ui.theme.TorfilxColors
import com.torfilx.core.ui.theme.TorfilxMotion
import com.torfilx.core.ui.theme.TorfilxShapes
import com.torfilx.core.ui.theme.TorfilxType
import com.torfilx.core.ui.theme.animateFocus
import com.torfilx.core.ui.theme.focusNudge
import com.torfilx.core.ui.theme.focusUnderline
import com.torfilx.core.ui.theme.ruleBelow

/** How far a focused link's arrow moves along. */
private val ARROW_NUDGE = 6.dp

/**
 * The button used in dialogs, menus, settings and the player: a hairline box with its label in spaced
 * capitals.
 *
 * Focus fills it with ivory and turns the label ink, at once — an inverted box reads from across a
 * room, and a control that has to be found should not wait on an animation. [primary] draws the
 * outline in ivory rather than as a faint rule.
 *
 * Built directly on `clickable` rather than a Material button so every control in the app is focused
 * the same way (plan.md §4).
 */
@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    autoFocus: Boolean = false,
    leadingIcon: (@Composable () -> Unit)? = null,
    focusRequester: FocusRequester? = null,
) {
    val dimens = LocalTorfilxDimens.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val requester = focusRequester ?: remember { FocusRequester() }
    AutoFocus(requester, autoFocus && enabled, text)

    val fill = if (isFocused && enabled) TorfilxColors.Accent else TorfilxColors.Transparent
    val outline = when {
        isFocused && enabled -> TorfilxColors.Accent
        primary && enabled -> TorfilxColors.FocusFrame
        else -> TorfilxColors.Rule
    }
    val content = when {
        !enabled -> TorfilxColors.TextDisabled
        isFocused -> TorfilxColors.TextOnAccent
        primary -> TorfilxColors.TextPrimary
        else -> TorfilxColors.TextSecondary
    }

    Box(
        modifier = modifier
            .background(fill, TorfilxShapes.Control)
            .border(dimens.hairline, outline, TorfilxShapes.Control)
            .focusRequester(requester)
            // `clickable` already makes the node focusable AND handles DPAD_CENTER/Enter. A separate
            // `focusable()` steals the focus target, so the control highlights but OK does nothing —
            // the classic "the remote is dead" bug on TV.
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .semantics { role = Role.Button }
            .padding(horizontal = 22.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            leadingIcon?.invoke()
            CapsText(text = text, color = content)
        }
    }
}

/**
 * An action set as a line of type, for the hero and the details spread: "PLAY EPISODE ONE →".
 *
 * At rest it is a dim label. Focus brightens it at once, then an underline draws across beneath it and
 * the [arrow], if any, steps forward. The movement is drawn, not laid out, so it costs no recomposition.
 */
@Composable
fun TvTextLink(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    arrow: Boolean = false,
    enabled: Boolean = true,
    autoFocus: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    val dimens = LocalTorfilxDimens.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val focus = animateFocus(isFocused, label = "linkFocus")
    val requester = focusRequester ?: remember { FocusRequester() }
    AutoFocus(requester, autoFocus && enabled, text)

    val color = when {
        !enabled -> TorfilxColors.TextDisabled
        isFocused -> TorfilxColors.TextPrimary
        else -> TorfilxColors.TextTertiary
    }

    Row(
        modifier = modifier
            .focusRequester(requester)
            .clickable(enabled = enabled, interactionSource = interactionSource, indication = null, onClick = onClick)
            .semantics { role = Role.Button }
            .focusUnderline(focus, dimens.underline)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CapsText(text = text, color = color)
        if (arrow) {
            Text(
                text = "→",
                style = TorfilxType.LabelCaps,
                color = color,
                modifier = Modifier.focusNudge(focus, ARROW_NUDGE),
            )
        }
    }
}

/**
 * A choice among several — a sort order, a season, a subtitle track — set as a spaced label.
 *
 * The chosen one is ivory with a fine rule under it; the others are dim. Focus brightens a chip and
 * draws a full underline across it, so the focused chip and the chosen one never look alike.
 */
@Composable
fun TvChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalTorfilxDimens.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val focus = animateFocus(isFocused, durationMs = TorfilxMotion.FOCUS_MS, label = "chipFocus")

    Box(
        modifier = modifier
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            // So VoiceView announces the selected state, not just the label.
            .semantics {
                this.selected = selected
                this.role = Role.Tab
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        CapsText(
            text = text,
            color = if (isFocused || selected) TorfilxColors.TextPrimary else TorfilxColors.TextTertiary,
            modifier = Modifier
                .then(if (selected) Modifier.ruleBelow(dimens.hairline, TorfilxColors.TextTertiary) else Modifier)
                .focusUnderline(focus, dimens.underline)
                .padding(bottom = 6.dp),
        )
    }
}

/** Takes focus when [enabled] becomes true. */
@Composable
private fun AutoFocus(requester: FocusRequester, enabled: Boolean, name: String) {
    LaunchedEffect(enabled) {
        if (enabled) {
            runCatching { requester.requestFocus() }
                .onFailure { TorfilxLog.w("TvControls", "autoFocus failed for '$name'") }
        }
    }
}
