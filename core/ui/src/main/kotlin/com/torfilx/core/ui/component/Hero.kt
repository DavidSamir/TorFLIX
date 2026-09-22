package com.torfilx.core.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.torfilx.core.model.MediaCard
import com.torfilx.core.ui.image.Artwork
import com.torfilx.core.ui.theme.LocalTorfilxDimens
import com.torfilx.core.ui.theme.TorfilxColors
import com.torfilx.core.ui.util.Format
import kotlinx.coroutines.delay

private const val AUTO_ADVANCE_MS = 8_000L
private const val CROSSFADE_MS = 300

/**
 * The featured banner at the top of Home.
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

    LaunchedEffect(items.size, userInteracted, hasFocus) {
        if (items.size <= 1 || userInteracted || hasFocus) return@LaunchedEffect
        while (true) {
            delay(AUTO_ADVANCE_MS)
            index = (index + 1) % items.size
        }
    }

    val current = items[index.coerceIn(items.indices)]

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(360.dp)
            .onFocusChanged { state ->
                hasFocus = state.hasFocus
                if (state.hasFocus) userInteracted = true
            }
            .focusGroup(),
    ) {
        AnimatedContent(
            targetState = current,
            transitionSpec = {
                fadeIn(tween(CROSSFADE_MS)) togetherWith fadeOut(tween(CROSSFADE_MS))
            },
            label = "heroBackdrop",
        ) { card ->
            Artwork(
                url = card.item.images.backdrop ?: card.item.images.poster,
                title = card.item.title,
                seed = card.item.id,
                // The hero already shows the title in 40 sp; a second copy inside the generated
                // backdrop would read as a rendering glitch.
                showGeneratedLabel = false,
                widthDp = 960.dp,
                heightDp = 360.dp,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Scrims: horizontal for text legibility, vertical to blend into the first row.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to TorfilxColors.ScrimStrong,
                        0.55f to TorfilxColors.ScrimSoft,
                        1f to TorfilxColors.Transparent,
                    ),
                ),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.6f to TorfilxColors.Transparent,
                        1f to TorfilxColors.Background,
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = dimens.overscanHorizontal)
                .width(560.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = current.item.title,
                style = MaterialTheme.typography.displayMedium,
                color = TorfilxColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // A show on the hero is there for one episode — the one its button plays — so say which.
            current.episode?.let { episode ->
                Text(
                    text = "${episode.code} · ${episode.displayName}",
                    style = MaterialTheme.typography.titleMedium,
                    color = TorfilxColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = Format.metaLine(current.item),
                style = MaterialTheme.typography.labelLarge,
                color = TorfilxColors.TextSecondary,
                maxLines = 1,
            )
            (current.episode?.overview ?: current.item.overview)?.takeIf { it.isNotBlank() }?.let { overview ->
                Text(
                    text = overview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TorfilxColors.TextSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 6.dp),
            ) {
                TvButton(
                    text = primaryActionLabel(current),
                    onClick = { onPlay(current) },
                    focusRequester = playFocusRequester,
                )
                TvButton(
                    text = if (current.inMyList) "✓ My List" else "+ My List",
                    onClick = { onToggleMyList(current) },
                    primary = false,
                )
                TvButton(
                    text = "More info",
                    onClick = { onMoreInfo(current) },
                    primary = false,
                )
            }
        }

        if (items.size > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = dimens.overscanHorizontal, bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items.indices.forEach { position ->
                    Box(
                        Modifier
                            .width(if (position == index) 20.dp else 8.dp)
                            .height(4.dp)
                            .background(
                                if (position == index) TorfilxColors.TextPrimary else TorfilxColors.TextTertiary,
                            ),
                    )
                }
            }
        }
    }
}
