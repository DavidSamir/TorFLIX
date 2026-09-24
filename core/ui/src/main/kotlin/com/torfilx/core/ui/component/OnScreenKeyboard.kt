package com.torfilx.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.torfilx.core.ui.theme.LocalTorfilxDimens
import com.torfilx.core.ui.theme.TorfilxColors
import com.torfilx.core.ui.theme.TorfilxShapes
import com.torfilx.core.ui.theme.TorfilxType
import com.torfilx.core.ui.theme.ruleBelow

/** Which character set the on-screen keyboard is showing. */
enum class KeyboardLayout(val label: String) {
    LATIN("ABC"),
    NUMBERS("123"),
}

private val LATIN_ROWS = listOf(
    "ABCDEF",
    "GHIJKL",
    "MNOPQR",
    "STUVWX",
    "YZ'-",
)

/** At most six to a row, like the letters, so the keyboard stays inside its column. */
private val NUMBER_ROWS = listOf(
    "123456",
    "7890",
    ".,:!?&",
)

/**
 * A grid keyboard driven entirely by the D-pad.
 *
 * The Fire TV system IME is an overlay that steals focus and behaves unpredictably inside Compose,
 * so search and text entry use this instead — the same choice Netflix makes (plan.md §6.5).
 *
 * It must fit on the screen whole, under the search field: five letter rows and one row of actions. It
 * used to take eight rows, so its last rows were drawn off the bottom of the screen, and Up from its top
 * row sent focus down to a key nobody could see instead of up to the tab bar.
 */
@Composable
fun OnScreenKeyboard(
    onCharacter: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    layout: KeyboardLayout = KeyboardLayout.LATIN,
    onLayoutChange: (KeyboardLayout) -> Unit = {},
    firstKeyFocusRequester: FocusRequester? = null,
) {
    val rows = when (layout) {
        KeyboardLayout.LATIN -> LATIN_ROWS
        KeyboardLayout.NUMBERS -> NUMBER_ROWS
    }
    val other = KeyboardLayout.entries.first { it != layout }

    Column(
        modifier = modifier.focusGroup(),
        verticalArrangement = Arrangement.spacedBy(KEY_GAP),
    ) {
        rows.forEachIndexed { rowIndex, rowChars ->
            Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                rowChars.forEachIndexed { charIndex, character ->
                    KeyboardKey(
                        label = character.toString(),
                        onClick = { onCharacter(character) },
                        focusRequester = if (rowIndex == 0 && charIndex == 0) {
                            firstKeyFocusRequester
                        } else {
                            null
                        },
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
            KeyboardKey(label = "space", width = 96.dp, onClick = { onCharacter(' ') })
            KeyboardKey(label = "⌫", onClick = onBackspace, description = "Backspace")
            // One key that switches to the other layout, labelled with where it goes.
            KeyboardKey(
                label = other.label,
                width = 64.dp,
                onClick = { onLayoutChange(other) },
                description = "Switch to ${other.label}",
            )
            KeyboardKey(label = "clear", width = 72.dp, onClick = onClear)
        }
    }
}

private val KEY_HEIGHT = 44.dp
private val KEY_GAP = 6.dp

@Composable
private fun KeyboardKey(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp = 48.dp,
    description: String? = null,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val hairline = LocalTorfilxDimens.current.hairline
    val color = if (isFocused) TorfilxColors.TextOnAccent else TorfilxColors.TextPrimary

    // A key is found by eye across a grid of them, so focus inverts it at once rather than easing in.
    Box(
        modifier = modifier
            .size(width = width, height = KEY_HEIGHT)
            .background(if (isFocused) TorfilxColors.Accent else TorfilxColors.Transparent, TorfilxShapes.Control)
            .border(hairline, if (isFocused) TorfilxColors.Accent else TorfilxColors.Rule, TorfilxShapes.Control)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .semantics { contentDescription = description ?: label },
        contentAlignment = Alignment.Center,
    ) {
        // Letters and marks in the serif; the word keys ("space", "clear", "ABC") in spaced capitals.
        if (label.length == 1) {
            Text(text = label, style = TorfilxType.ListTitle, color = color)
        } else {
            CapsText(text = label, style = TorfilxType.MetaCaps, color = color)
        }
    }
}

/** The placeholder, in italics at the size of the query it stands in for, so the field never changes height. */
private val PLACEHOLDER_STYLE = TorfilxType.Deck.copy(fontSize = TorfilxType.SectionTitle.fontSize, lineHeight = TorfilxType.SectionTitle.lineHeight)

/** The text field that the on-screen keyboard types into: the query set in the serif, over a rule. */
@Composable
fun SearchField(
    value: String,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .ruleBelow(LocalTorfilxDimens.current.hairline, TorfilxColors.TextTertiary)
            // Announce it as the search field and read back the current query for VoiceView, which
            // otherwise sees only a static Text.
            .semantics {
                contentDescription = if (value.isEmpty()) placeholder else "Search: $value"
            }
            .padding(vertical = 10.dp),
    ) {
        Text(
            text = value.ifEmpty { placeholder },
            style = if (value.isEmpty()) PLACEHOLDER_STYLE else TorfilxType.SectionTitle,
            color = if (value.isEmpty()) TorfilxColors.TextTertiary else TorfilxColors.TextPrimary,
            maxLines = 1,
        )
    }
}
