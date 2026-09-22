package com.torfilx.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.core.ui.theme.animateFocusScale
import com.torfilx.core.ui.theme.TorfilxColors

/** Buttons zoom less than cards: they sit in rows, and a big zoom would overlap the next one. */
private const val BUTTON_FOCUS_SCALE = 1.06f

/**
 * The one button used everywhere.
 *
 * Built directly on `focusable` + `clickable` rather than a Material button so the focus visuals are
 * identical to the cards: a scale-up plus a solid border, never a colour-only change (plan.md §4).
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
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFocusScale(isFocused, focusedScale = BUTTON_FOCUS_SCALE, label = "buttonScale")
    val requester = focusRequester ?: remember { FocusRequester() }

    LaunchedEffect(autoFocus, enabled) {
        if (autoFocus && enabled) {
            runCatching { requester.requestFocus() }
                .onFailure { TorfilxLog.w("TvButton", "autoFocus failed for '$text'") }
        }
    }

    val background = when {
        !enabled -> TorfilxColors.SurfaceLow
        isFocused && primary -> TorfilxColors.TextPrimary
        isFocused -> TorfilxColors.SurfaceHighest
        primary -> TorfilxColors.Accent
        else -> TorfilxColors.SurfaceHigh
    }
    val contentColor = when {
        !enabled -> TorfilxColors.TextTertiary
        isFocused && primary -> TorfilxColors.Background
        else -> TorfilxColors.TextPrimary
    }

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) TorfilxColors.Focus else TorfilxColors.Transparent,
                shape = RoundedCornerShape(6.dp),
            )
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
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            leadingIcon?.invoke()
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Selectable chip used for sort/filter and season selection. */
@Composable
fun TvChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val background = when {
        isFocused -> TorfilxColors.TextPrimary
        selected -> TorfilxColors.SurfaceHighest
        else -> TorfilxColors.SurfaceLow
    }
    val contentColor = if (isFocused) TorfilxColors.Background else TorfilxColors.TextPrimary

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(background)
            .border(
                width = if (isFocused) 2.dp else if (selected) 1.dp else 0.dp,
                color = when {
                    isFocused -> TorfilxColors.Focus
                    selected -> TorfilxColors.TextSecondary
                    else -> TorfilxColors.Transparent
                },
                shape = RoundedCornerShape(20.dp),
            )
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            // So VoiceView announces the on/off or selected state, not just the label.
            .semantics {
                this.selected = selected
                this.role = Role.Tab
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            maxLines = 1,
        )
    }
}
