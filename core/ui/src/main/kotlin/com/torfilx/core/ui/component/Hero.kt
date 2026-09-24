package com.torfilx.core.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.torfilx.core.model.MediaCard
import com.torfilx.core.model.MediaItem
import com.torfilx.core.ui.focus.bringIntoViewAsWhole
import com.torfilx.core.ui.image.Artwork
import com.torfilx.core.ui.theme.LocalReduceMotion
import com.torfilx.core.ui.theme.LocalTorfilxDimens
import com.torfilx.core.ui.theme.TorfilxColors
import com.torfilx.core.ui.theme.TorfilxMotion
import com.torfilx.core.ui.theme.TorfilxShapes
import com.torfilx.core.ui.theme.TorfilxType
import com.torfilx.core.ui.util.Format
import kotlinx.coroutines.delay

private const val AUTO_ADVANCE_MS = 8_000L

private val HERO_HEIGHT = 372.dp
private val PLATE_POSTER_WIDTH = 176.dp
private val PLATE_POSTER_HEIGHT = 264.dp
private val PLATE_MAT = 8.dp

/**
 * The featured title at the top of Home, set as the opening spread of a magazine: a kicker, the title
 * in large type, an italic deck, a paragraph, and the actions as a line of text links — beside the
 * poster, framed as a plate with its caption.
 *
 * No full-bleed backdrop or gradient scrims: one poster at plate size is a fraction of the pixels to
 * decode and composite, on the sticks where that matters most.
 *
 * Auto-advance stops permanently once the user interacts, and never runs while the hero has focus —
 * content sliding out from under a focused button is the most annoying thing a TV UI can do
 * (plan.md §6.1).
 */
@Composable
fun HeroSection(
    items: List<MediaCard>,
    primaryActionLabel: (MediaCard) -> String,
    onPlay: (MediaCard) -> Unit,
    onMoreInfo: (MediaCard) -> Unit,
    onToggleMyList: (MediaCard) -> Unit,
    modifier: Modifier = Modifier,
    playFocusRequester: FocusRequester? = null,
) {
    if (items.isEmpty()) return
    val dimens = LocalTorfilxDimens.current
    var index by remember(items.size) { mutableIntStateOf(0) }
    var userInteracted by remember { mutableStateOf(false) }
    var hasFocus by remember { mutableStateOf(false) }
    // Reduce motion: the hero holds still, and a change of title is a cut rather than a fade.
    val reduceMotion = LocalReduceMotion.current

    LaunchedEffect(items.size, userInteracted, hasFocus, reduceMotion) {
        if (items.size <= 1 || userInteracted || hasFocus || reduceMotion) return@LaunchedEffect
        while (true) {
            delay(AUTO_ADVANCE_MS)
            index = (index + 1) % items.size
        }
    }

    val position = index.coerceIn(items.indices)
    val current = items[position]

    // The words fade in when the title changes. Read in a graphicsLayer, so the fade only redraws.
    val textAlpha = remember { Animatable(1f) }
    var shownId by remember { mutableStateOf(current.playableId) }
    LaunchedEffect(current.playableId) {
        if (current.playableId == shownId) return@LaunchedEffect
        shownId = current.playableId
        if (!reduceMotion) {
            textAlpha.snapTo(0f)
            textAlpha.animateTo(1f, tween(TorfilxMotion.CROSSFADE_MS, easing = TorfilxMotion.Ease))
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(HERO_HEIGHT)
            // Focus on a link shows the whole hero, so its kicker and title never scroll away.
            .bringIntoViewAsWhole()
            .onFocusChanged { state ->
                hasFocus = state.hasFocus
                if (state.hasFocus) userInteracted = true
            }
            .focusGroup()
            .padding(horizontal = dimens.overscanHorizontal)
            .padding(top = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(56.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Column(
                modifier = Modifier.graphicsLayer { alpha = textAlpha.value },
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Kicker(text = "The feature · " + kindAndYear(current.item))
                Text(
                    text = current.item.title,
                    style = TorfilxType.displayFor(current.item.title),
                    color = TorfilxColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                deckFor(current).takeIf { it.isNotEmpty() }?.let { deck ->
                    Text(
                        text = deck,
                        style = TorfilxType.Deck,
                        color = TorfilxColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                (current.episode?.overview ?: current.item.overview)?.takeIf { it.isNotBlank() }?.let { overview ->
                    Text(
                        text = overview,
                        style = TorfilxType.Reading,
                        color = TorfilxColors.TextSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(40.dp),
                modifier = Modifier.padding(top = 20.dp),
            ) {
                TvTextLink(
                    text = primaryActionLabel(current),
                    onClick = { onPlay(current) },
                    arrow = true,
                    focusRequester = playFocusRequester,
                )
                TvTextLink(
                    text = if (current.inMyList) "In My List ✓" else "Add to My List",
                    onClick = { onToggleMyList(current) },
                )
                TvTextLink(
                    text = if (current.item.isShow) "About the series" else "About the film",
                    onClick = { onMoreInfo(current) },
                )
            }
        }

        Plate(
            item = current.item,
            label = plateLabel(position, items.size),
            crossfadeMs = if (reduceMotion) 0 else TorfilxMotion.CROSSFADE_MS,
        )
    }
}

/**
 * A poster framed like a plate in a book: a hairline mat around it and an italic caption below,
 * led by a spaced label.
 */
@Composable
fun Plate(
    item: MediaItem,
    label: String,
    modifier: Modifier = Modifier,
    crossfadeMs: Int = TorfilxMotion.CROSSFADE_MS,
    posterWidth: Dp = PLATE_POSTER_WIDTH,
    posterHeight: Dp = PLATE_POSTER_HEIGHT,
    imageUrl: String? = item.images.poster,
) {
    val dimens = LocalTorfilxDimens.current
    val image = remember(item.id, imageUrl) { PlateImage(item.id, item.title, imageUrl) }
    Column(modifier.width(posterWidth + PLATE_MAT * 2)) {
        Box(
            Modifier
                .border(dimens.hairline, TorfilxColors.Rule, TorfilxShapes.Card)
                .padding(PLATE_MAT)
                .size(posterWidth, posterHeight)
                .background(TorfilxColors.SurfaceLow, TorfilxShapes.Card),
        ) {
            AnimatedContent(
                targetState = image,
                transitionSpec = { fadeIn(tween(crossfadeMs)) togetherWith fadeOut(tween(crossfadeMs)) },
                contentKey = { it.id },
                label = "plate",
            ) { shown ->
                Artwork(
                    url = shown.url,
                    title = shown.title,
                    seed = shown.id,
                    widthDp = posterWidth,
                    heightDp = posterHeight,
                    modifier = Modifier.size(posterWidth, posterHeight),
                )
            }
        }
        val caption = remember(item.id, item.title, item.year, label) {
            buildAnnotatedString {
                // Upright: the caption around it is italic, and Inter has no italic cut to fall back on.
                withStyle(TorfilxType.MetaCaps.toSpanStyle().copy(color = TorfilxColors.TextPrimary, fontStyle = FontStyle.Normal)) {
                    append(label.uppercase())
                }
                append("   ")
                append(item.title)
                item.year?.let { append(", $it") }
                append(".")
            }
        }
        Text(
            text = caption,
            style = TorfilxType.Caption,
            color = TorfilxColors.TextTertiary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/** What a plate shows; the key its crossfade follows. */
private data class PlateImage(val id: String, val title: String, val url: String?)

/** The spaced line above a title, led by a short rule. */
@Composable
fun Kicker(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .width(28.dp)
                .height(LocalTorfilxDimens.current.hairline)
                .background(TorfilxColors.TextTertiary),
        )
        CapsText(text = text, style = TorfilxType.KickerCaps, color = TorfilxColors.TextTertiary)
    }
}

/** "Series, 2008", "Film, 1921", or just the kind when the year is unknown. */
fun kindAndYear(item: MediaItem): String {
    val kind = if (item.isShow) "Series" else "Film"
    return item.year?.let { "$kind, $it" } ?: kind
}

/** "Plate I", or "Plate II of V" when there are several. */
internal fun plateLabel(position: Int, count: Int): String {
    val number = Format.sectionNumeral(position + 1).removeSuffix(".").uppercase()
    return if (count > 1) {
        "Plate $number of ${Format.sectionNumeral(count).removeSuffix(".").uppercase()}"
    } else {
        "Plate $number"
    }
}

/**
 * The italic line under the hero's title: for an episode, which one; otherwise the genres and how long
 * it runs — "Crime, Drama, Thriller — 5 seasons".
 */
internal fun deckFor(card: MediaCard): String {
    card.episode?.let { return "${it.code} — ${it.displayName}" }
    val item = card.item
    val length = if (item.isShow) Format.showLength(item) else Format.runtime(item.runtimeMs)
    return listOf(item.genres.take(3).joinToString(", "), length)
        .filter { it.isNotEmpty() }
        .joinToString(" — ")
}
