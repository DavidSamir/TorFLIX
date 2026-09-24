package com.torfilx.core.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import com.torfilx.core.ui.theme.TorfilxType

/**
 * Text set in spaced capitals, the editorial label.
 *
 * The capitals are only drawn: VoiceView and tests are given [text] as written, because a screen reader
 * spells out some all-capital words letter by letter.
 */
@Composable
fun CapsText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = TorfilxType.LabelCaps,
    maxLines: Int = 1,
    textAlign: TextAlign? = null,
) {
    val caps = remember(text) { text.uppercase() }
    Text(
        text = caps,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
        modifier = modifier.clearAndSetSemantics { this.text = AnnotatedString(text) },
    )
}
