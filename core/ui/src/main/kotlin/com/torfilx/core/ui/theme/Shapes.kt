package com.torfilx.core.ui.theme

import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape

/**
 * Corners. A printed page has none, so every shape is square.
 *
 * Square is also the cheapest shape to draw: a background or border on a [RectangleShape] is a plain
 * rectangle, and nothing needs a clipping layer. Pass these to `background` and `border`; avoid
 * `clip`, which would add a layer for no visible change.
 */
object TorfilxShapes {
    /** Posters, stills, plates. */
    val Card: Shape = RectangleShape

    /** Buttons, keys, fields. */
    val Control: Shape = RectangleShape

    /** Dialogs, menus, overlays. */
    val Panel: Shape = RectangleShape
}
