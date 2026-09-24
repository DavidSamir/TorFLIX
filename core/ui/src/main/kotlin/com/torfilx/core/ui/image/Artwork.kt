package com.torfilx.core.ui.image

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.Scale
import com.torfilx.core.ui.theme.TorfilxColors
import com.torfilx.core.ui.theme.TorfilxType

private const val CROSSFADE_MS = 150
private const val DEMO_SCHEME = "demo://"

/**
 * Artwork with a *designed* fallback.
 *
 * A personal library always has items with no poster, so the fallback is a first-class visual: a
 * deterministic gradient derived from the item id plus the title, not a broken-image icon
 * (plan.md §4, §10). Demo-mode URLs render the same way, which is why the demo library needs no
 * network at all.
 */
@Composable
fun Artwork(
    url: String?,
    title: String,
    modifier: Modifier = Modifier,
    seed: String = title,
    /** Generated fallbacks draw the title; switch it off where the title is already on screen. */
    showGeneratedLabel: Boolean = true,
    widthDp: Dp? = null,
    heightDp: Dp? = null,
    contentScale: ContentScale = ContentScale.Crop,
    contentDescription: String? = null,
) {
    val isGenerated = url.isNullOrBlank() || url.startsWith(DEMO_SCHEME)
    if (isGenerated) {
        GeneratedArtwork(
            title = if (showGeneratedLabel) title else "",
            seed = seed,
            modifier = modifier,
        )
        return
    }

    val density = LocalDensity.current
    val context = LocalContext.current
    val request = remember(url, widthDp, heightDp) {
        ImageRequest.Builder(context)
            .data(sizedUrl(url!!, widthDp, heightDp, density.density))
            .crossfade(CROSSFADE_MS)
            // Requesting an inexact size lets Coil reuse a cached bitmap of a near-enough size
            // instead of decoding the same poster once per row.
            .precision(Precision.INEXACT)
            .scale(Scale.FILL)
            .apply {
                if (widthDp != null && heightDp != null) {
                    size(
                        with(density) { widthDp.roundToPx() },
                        with(density) { heightDp.roundToPx() },
                    )
                }
            }
            .build()
    }

    // A poster URL can be dead, or the TV can be offline. Either way the card must still look
    // deliberate, so a failed load falls back to the same generated art as a missing poster.
    var failed by remember(url) { mutableStateOf(false) }
    if (failed) {
        GeneratedArtwork(title = if (showGeneratedLabel) title else "", seed = seed, modifier = modifier)
        return
    }

    Box(modifier.background(TorfilxColors.SurfaceLow)) {
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            contentScale = contentScale,
            onError = { failed = true },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Appends the server's `?w=` resize hint so a 150 dp poster never downloads a 2000 px file
 * (plan.md §11.4).
 */
private fun sizedUrl(url: String, widthDp: Dp?, heightDp: Dp?, density: Float): String {
    val targetWidthPx = widthDp?.value?.times(density)?.toInt() ?: return url
    if (url.contains("?w=") || url.contains("&w=")) return url
    val bucket = when {
        targetWidthPx <= 200 -> 300
        targetWidthPx <= 400 -> 500
        targetWidthPx <= 800 -> 960
        else -> 1280
    }
    val separator = if (url.contains('?')) '&' else '?'
    return "$url${separator}w=$bucket"
}

/**
 * A title with no artwork, set like a plain book cover: the title in italics on a deep, warm field,
 * inside a hairline border. The field's tone comes from the item id, so the same title always looks
 * the same.
 */
@Composable
private fun GeneratedArtwork(title: String, seed: String, modifier: Modifier) {
    val brush = remember(seed) { Brush.verticalGradient(gradientFor(seed)) }
    Box(
        modifier = modifier
            .background(brush)
            .padding(8.dp)
            .border(1.dp, TorfilxColors.Rule),
        contentAlignment = Alignment.Center,
    ) {
        if (title.isNotEmpty()) {
            Text(
                text = title,
                style = TorfilxType.CardTitle,
                color = TorfilxColors.TextPrimary,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

/** Deep, warm fields that sit with the ivory type: oxblood, forest, ink blue, umber, plum, slate. */
private val COVER_FIELDS = listOf(
    Color(0xFF3A1D1B) to Color(0xFF1C1110),
    Color(0xFF1E2D22) to Color(0xFF111812),
    Color(0xFF1C2433) to Color(0xFF10141B),
    Color(0xFF33291C) to Color(0xFF1A1510),
    Color(0xFF2E1F2C) to Color(0xFF171016),
    Color(0xFF262A2B) to Color(0xFF141617),
)

/** Deterministic two-stop gradient: the same item always gets the same artwork. */
private fun gradientFor(seed: String): List<Color> {
    // mod, not abs: the absolute value of Int.MIN_VALUE is still negative.
    val (start, end) = COVER_FIELDS[seed.hashCode().mod(COVER_FIELDS.size)]
    return listOf(start, end)
}
