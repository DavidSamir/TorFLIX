package com.torfilx.feature.player

import android.app.Activity
import android.graphics.Color
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.torfilx.core.model.SubtitleSize
import com.torfilx.core.model.SubtitleStyle
import com.torfilx.core.player.AspectMode
import com.torfilx.core.player.EndCard
import com.torfilx.core.player.PlaybackError
import com.torfilx.core.player.PlayerUiState
import com.torfilx.core.player.StreamStats
import com.torfilx.core.ui.component.ErrorState
import com.torfilx.core.ui.component.NextEpisodeCard
import com.torfilx.core.ui.component.SharingConsentDialog
import com.torfilx.core.ui.component.TvButton
import com.torfilx.core.ui.component.TvChip
import com.torfilx.core.ui.focus.RemoteKeys
import com.torfilx.core.ui.focus.onRemoteKey
import com.torfilx.core.ui.theme.TorfilxColors
import com.torfilx.core.ui.util.Format
import kotlinx.coroutines.delay

private const val CONTROLS_TIMEOUT_MS = 4_000L
private const val SEEK_COMMIT_DELAY_MS = 300L
private const val BUFFERING_SPINNER_DELAY_MS = 500L

/** How hard to try to put focus back on the root after the overlay closes. */
private const val FOCUS_RESTORE_ATTEMPTS = 5
private const val FOCUS_RESTORE_RETRY_MS = 32L

/**
 * The full-screen player.
 *
 * Key handling follows the plan (§5.1, §7.4): ←/→ always seek (never move focus), OK toggles the
 * overlay or activates the focused control, and the first Back press hides the overlay while the
 * second leaves — so nobody exits a film by accident.
 */
@Composable
fun PlayerScreen(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val controlsVisible by viewModel.controlsVisible.collectAsStateWithLifecycle()
    val controlsRevision by viewModel.controlsRevision.collectAsStateWithLifecycle()
    val pendingSeek by viewModel.pendingSeekMs.collectAsStateWithLifecycle()
    val displaySwitching by viewModel.displaySwitching.collectAsStateWithLifecycle()
    val autoSkipIntro by viewModel.skipIntroAutomatically.collectAsStateWithLifecycle()
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val seekStepMs = prefs.seekStep.ms
    val longSeekStepMs = seekStepMs * LONG_SEEK_MULTIPLIER
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val rootFocus = remember { FocusRequester() }

    // Keep the screen awake while playing OR waiting for data, but let it sleep when genuinely
    // paused/idle. The window flag is authoritative; PlayerView no longer forces it on unconditionally.
    KeepScreenOn(active = state.isPlaying || state.isBuffering || state.isLoading)
    PauseWhenBackgrounded(onBackground = viewModel::onBackground, onForeground = viewModel::onForeground)
    val modalUp = state.endCard != null || state.showStillWatching

    // The end card sits over the last frame on its own; the controls would only compete for focus.
    LaunchedEffect(state.endCard != null) {
        if (state.endCard != null) viewModel.hideControls()
    }

    // Commit an accumulated seek once the user stops pressing the key.
    LaunchedEffect(pendingSeek) {
        if (pendingSeek != null) {
            delay(SEEK_COMMIT_DELAY_MS)
            viewModel.commitSeek()
        }
    }

    // Auto-hide the overlay, but never while a modal decision is on screen.
    //
    // Keyed on the interaction revision as well as visibility: every key press bumps it, which
    // cancels and restarts this effect, so the countdown measures *idle* time rather than time since
    // the overlay opened. Without it the overlay hid itself mid-interaction.
    LaunchedEffect(controlsVisible, controlsRevision, state.isPlaying, state.showStillWatching) {
        if (controlsVisible && state.isPlaying && !state.showStillWatching) {
            delay(CONTROLS_TIMEOUT_MS)
            viewModel.hideControls()
        }
    }

    // Keep D-pad input alive. While the overlay is up its scrubber holds focus; when it hides, the
    // focused controls subtree leaves composition, so focus must return to the root Box or the
    // remote goes dead (Up/Down would no longer reach the handler that reopens the overlay).
    //
    // The request is retried: a FocusRequester whose node is not yet attached — which is exactly the
    // case in the frame where the overlay is leaving composition — throws, and a single silent
    // failure here is indistinguishable from a broken remote.
    // Not while a card or "still watching" is up: their buttons hold focus, and pulling it back to the
    // root would leave OK doing nothing on a screen that is asking a question.
    LaunchedEffect(controlsVisible, modalUp) {
        if (controlsVisible || modalUp) return@LaunchedEffect
        repeat(FOCUS_RESTORE_ATTEMPTS) { attempt ->
            if (runCatching { rootFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(FOCUS_RESTORE_RETRY_MS * (attempt + 1))
        }
    }

    // Auto-skip fires once per intro window, not on every frame of it.
    LaunchedEffect(state.showSkipIntro, autoSkipIntro) {
        if (autoSkipIntro && state.showSkipIntro) viewModel.skipIntro()
    }

    BackHandler(enabled = true) {
        if ((state.endCard as? EndCard.Next)?.midEpisode == true) {
            viewModel.dismissNextEpisodeCard()
        } else if (controlsVisible) {
            viewModel.hideControls()
        } else {
            viewModel.leavePlayer()
            onExit()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TorfilxColors.Background)
            .focusRequester(rootFocus)
            .focusable()
            .onRemoteKey { key, isKeyDown, _ ->
                if (!isKeyDown) return@onRemoteKey false
                // With a card or "still watching" up, the D-pad and OK belong to its buttons. A key on the
                // end card still says someone is there; "still watching" must be answered by its buttons.
                if (modalUp) {
                    if (!state.showStillWatching) viewModel.noteUserInput()
                    return@onRemoteKey false
                }
                viewModel.noteUserInput()
                when (key) {
                    // ←/→ seek only while the overlay is hidden. With the overlay up they belong to
                    // whatever control is focused — moving between the buttons, or scrubbing when the
                    // timeline itself holds focus — so the root must not swallow them.
                    RemoteKeys.LEFT -> {
                        if (controlsVisible) false else { viewModel.nudgeSeek(-seekStepMs); true }
                    }

                    RemoteKeys.RIGHT -> {
                        if (controlsVisible) false else { viewModel.nudgeSeek(seekStepMs); true }
                    }

                    // The remote's dedicated transport keys always seek, overlay or not.
                    RemoteKeys.REWIND -> {
                        viewModel.nudgeSeek(-longSeekStepMs); true
                    }

                    RemoteKeys.FAST_FORWARD -> {
                        viewModel.nudgeSeek(longSeekStepMs); true
                    }

                    RemoteKeys.PLAY_PAUSE, RemoteKeys.PLAY, RemoteKeys.PAUSE -> {
                        viewModel.togglePlayPause(); true
                    }

                    in RemoteKeys.CONFIRM_KEYS -> {
                        if (!controlsVisible) {
                            viewModel.togglePlayPause()
                            true
                        } else {
                            false // let the focused control handle it
                        }
                    }

                    RemoteKeys.UP, RemoteKeys.DOWN, RemoteKeys.MENU -> {
                        viewModel.showControls()
                        key == RemoteKeys.MENU
                    }

                    else -> false
                }
            },
    ) {
        VideoSurface(
            viewModel = viewModel,
            aspectMode = state.aspectMode,
            subtitleSize = prefs.subtitleSize,
            subtitleStyle = prefs.subtitleStyle,
        )

        // While the TV renegotiates HDMI after a refresh-rate switch it shows garbage; cover it.
        if (displaySwitching) {
            Box(Modifier.fillMaxSize().background(TorfilxColors.Background))
        }

        // Loading counts as well as buffering. Resolving a magnet can take a minute on a cold swarm,
        // and until now that minute was a black screen with nothing on it — the same thing a hung app
        // looks like.
        if (state.isBuffering || state.isLoading) {
            BufferingIndicator(state = state)
        }

        // Video with no sound is not an error to ExoPlayer, so it has to be said out loud.
        state.audioUnavailableReason?.let { reason ->
            AudioUnavailableNotice(reason = reason, modifier = Modifier.align(Alignment.TopCenter))
        }

        // "Sharing is off" is not an error to report but a decision to ask for — the same dialog the
        // details screen shows, so every route into the player behaves identically.
        if (state.error is PlaybackError.SharingNotEnabled) {
            SharingConsentDialog(
                storageSummary = "While sharing, the app keeps part of what you watch on this device " +
                    "and uploads it to others, within the storage limit set in Settings.",
                onAccept = viewModel::enableSharingAndRetry,
                onDecline = {
                    viewModel.leavePlayer()
                    onExit()
                },
            )
            // return@Box, not return: a bare return leaves PlayerScreen itself, skipping the effects
            // declared after the Box. Box's content lambda is inline, so the compiler allows it and
            // the mistake is silent.
            return@Box
        }

        state.error?.let { error ->
            PlayerErrorOverlay(
                error = error,
                onRetry = viewModel::retry,
                onEnableSharing = viewModel::enableSharingAndRetry,
                onExit = {
                    viewModel.leavePlayer()
                    onExit()
                },
            )
            return@Box
        }

        if (state.showStillWatching) {
            StillWatchingOverlay(
                onContinue = { viewModel.dismissStillWatching(continueWatching = true) },
                onStop = {
                    viewModel.dismissStillWatching(continueWatching = false)
                    viewModel.leavePlayer()
                    onExit()
                },
            )
            return@Box
        }

        // An episode has ended: what comes next, over its last frame.
        state.endCard?.let { card ->
            EpisodeEndCard(
                card = card,
                onPlayNext = viewModel::playNext,
                onWatchAgain = viewModel::watchAgain,
                onKeepWatching = viewModel::dismissNextEpisodeCard,
                onBack = {
                    viewModel.leavePlayer()
                    onExit()
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 64.dp, bottom = 64.dp),
            )
            return@Box
        }

        // Opt-in, and only once there is something to report. Top right, clear of the controls, the
        // skip button and the end card.
        val stream = state.stream
        if (prefs.showStreamStats && stream != null && !state.isLoading) {
            StreamStatsOverlay(
                stream = stream,
                bufferedAheadMs = state.bufferedAheadMs,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 32.dp, end = 48.dp),
            )
        }

        if (state.showSkipIntro) {
            TvButton(
                text = "Skip intro",
                onClick = viewModel::skipIntro,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 64.dp, bottom = 120.dp),
                autoFocus = !controlsVisible,
            )
        }

        if (controlsVisible) {
            PlayerControls(
                state = state,
                pendingSeekMs = pendingSeek,
                seekStepMs = seekStepMs,
                onPlayPause = viewModel::togglePlayPause,
                onNudgeSeek = viewModel::nudgeSeek,
                onRestart = { viewModel.seekTo(0) },
                onSelectAudio = viewModel::selectAudioTrack,
                onSelectSubtitle = viewModel::selectSubtitleTrack,
                onAspect = viewModel::setAspectMode,
                onSpeed = viewModel::setSpeed,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    DisposableEffect(activity) {
        onDispose { activity?.let { /* display mode reset handled by FrameRateController */ } }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(
    viewModel: PlayerViewModel,
    aspectMode: AspectMode,
    subtitleSize: SubtitleSize,
    subtitleStyle: SubtitleStyle,
) {
    // Observed, not read once: when the player instance changes between titles the surface must
    // re-attach, or the next title plays with a black screen.
    val player by viewModel.playerFlow.collectAsStateWithLifecycle()
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            PlayerView(context).apply {
                useController = false // the overlay above is the controller
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                // Screen-on is driven by the window flag (KeepScreenOn) so it correctly clears when
                // paused; forcing it here kept the screen on even while paused.
            }
        },
        update = { view ->
            if (view.player !== player) view.player = player
            view.resizeMode = when (aspectMode) {
                AspectMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                AspectMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                AspectMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
            view.subtitleView?.applySubtitleLook(subtitleSize, subtitleStyle)
        },
        onReset = { view -> view.player = null },
    )
}

/**
 * Applies the subtitle size and colours from Settings.
 *
 * "TV default" hands both back to the TV's own caption settings, which is what PlayerView does on its
 * own. An explicit size also stops a subtitle file's own sizes from overriding it: the viewer asked
 * for large text, so a file asking for small text should not win.
 */
@OptIn(UnstableApi::class)
private fun SubtitleView.applySubtitleLook(size: SubtitleSize, style: SubtitleStyle) {
    if (size == SubtitleSize.TV_DEFAULT) {
        setUserDefaultTextSize()
        setApplyEmbeddedFontSizes(true)
    } else {
        setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * size.scale)
        setApplyEmbeddedFontSizes(false)
    }
    when (style) {
        SubtitleStyle.TV_DEFAULT -> setUserDefaultStyle()
        SubtitleStyle.OUTLINED -> setStyle(captionStyle(foreground = Color.WHITE, background = Color.TRANSPARENT))
        SubtitleStyle.BOXED -> setStyle(
            captionStyle(foreground = Color.WHITE, background = BOXED_BACKGROUND, edge = CaptionStyleCompat.EDGE_TYPE_NONE),
        )
        SubtitleStyle.YELLOW -> setStyle(captionStyle(foreground = Color.YELLOW, background = Color.TRANSPARENT))
    }
}

@OptIn(UnstableApi::class)
private fun captionStyle(
    foreground: Int,
    background: Int,
    edge: Int = CaptionStyleCompat.EDGE_TYPE_OUTLINE,
) = CaptionStyleCompat(
    foreground,
    background,
    /* windowColor = */ Color.TRANSPARENT,
    edge,
    /* edgeColor = */ Color.BLACK,
    /* typeface = */ null,
)

/** Black at 80%: dark enough to read over snow, light enough to keep the picture behind it. */
private const val BOXED_BACKGROUND = 0xCC000000.toInt()

/**
 * The opt-in "stats for nerds" line: what the swarm is doing for the title on screen.
 *
 * Deliberately one small line of text, not a panel: it is on screen for the whole film.
 */
@Composable
private fun StreamStatsOverlay(stream: StreamStats, bufferedAheadMs: Long, modifier: Modifier = Modifier) {
    val text = buildString {
        append("↓ ").append(Format.speed(stream.downloadBytesPerSecond))
        append("  ↑ ").append(Format.speed(stream.uploadBytesPerSecond))
        append("  ·  ").append(stream.peers).append(" peers")
        if (stream.seeds > 0) append(" (").append(stream.seeds).append(" seeds)")
        append("  ·  ").append((stream.progress * PERCENT).toInt()).append("% downloaded")
        val ahead = bufferedAheadMs / MS_PER_SECOND
        if (ahead > 0) append("  ·  ").append(ahead).append(" s buffered")
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = TorfilxColors.TextSecondary,
        modifier = modifier
            .background(TorfilxColors.ScrimStrong)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

private const val PERCENT = 100
private const val MS_PER_SECOND = 1_000L

/**
 * The spinner only appears after a short delay: on a LAN most rebuffers are shorter than that, and a
 * spinner that flashes on every seek looks broken (plan.md §7.4).
 */
@Composable
private fun BufferingIndicator(state: PlayerUiState) {
    val visibleState = remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(BUFFERING_SPINNER_DELAY_MS)
        visibleState.value = true
    }
    if (!visibleState.value) return

    val stream = state.stream
    // Headline reflects the actual stage, so a long wait is never a mystery: finding peers, fetching
    // the file listing, or genuinely buffering with peers connected.
    val headline = when {
        stream == null && state.isLoading -> "Starting…"
        stream == null -> "Buffering…"
        !stream.hasMetadata -> "Fetching file details…"
        stream.peers <= 0 -> "Looking for peers…"
        else -> "Buffering…"
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = headline,
                style = MaterialTheme.typography.titleLarge,
                color = TorfilxColors.TextPrimary,
            )
            if (stream != null) {
                val ahead = (state.bufferedAheadMs / 1000).coerceAtLeast(0)
                val detail = buildString {
                    append("${stream.peers} peers")
                    if (stream.seeds > 0) append(" · ${stream.seeds} seeds")
                    if (stream.downloadBytesPerSecond > 0) {
                        append(" · ${Format.speed(stream.downloadBytesPerSecond)}")
                    }
                    append(" · ${(stream.progress * 100).toInt()}% downloaded")
                    if (ahead > 0) append(" · ${ahead}s buffered")
                }
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelLarge,
                    color = TorfilxColors.TextSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            // e.g. that the swarm is being retried — otherwise a retry is indistinguishable from a hang.
            state.loadingDetail?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelLarge,
                    color = TorfilxColors.TextSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/**
 * Says that the film is playing without sound, and why.
 *
 * Deliberately non-modal and at the top of the screen: the video *is* playing and may still be worth
 * watching (a silent film, or one the viewer wants to see anyway), so this informs rather than
 * interrupts.
 */
@Composable
private fun AudioUnavailableNotice(reason: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .padding(top = 48.dp, start = 48.dp, end = 48.dp)
            .background(TorfilxColors.ScrimStrong)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "No sound",
            style = MaterialTheme.typography.titleLarge,
            color = TorfilxColors.Warning,
        )
        Text(
            text = reason,
            style = MaterialTheme.typography.labelLarge,
            color = TorfilxColors.TextSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun PlayerErrorOverlay(
    error: PlaybackError,
    onRetry: () -> Unit,
    onEnableSharing: () -> Unit,
    onExit: () -> Unit,
) {
    val sharingOff = error is PlaybackError.SharingNotEnabled
    ErrorState(
        title = when (error) {
            is PlaybackError.Network -> "Connection lost"
            is PlaybackError.Unsupported -> "Can't play this file"
            is PlaybackError.NotFound -> "Not available"
            is PlaybackError.Unauthorized -> "Access denied"
            is PlaybackError.SharingNotEnabled -> "Sharing is off"
            is PlaybackError.OutOfSpace -> "Not enough space"
            is PlaybackError.Unknown -> "Playback problem"
        },
        message = error.message,
        // Retrying is pointless for a format this device cannot decode, or for a consent decision
        // that has to be made in Settings.
        primaryActionLabel = when {
            sharingOff -> "Enable sharing"
            error.isRetryable() -> "Retry"
            else -> null
        },
        onPrimaryAction = when {
            sharingOff -> onEnableSharing
            error.isRetryable() -> onRetry
            else -> null
        },
        secondaryActionLabel = "Back",
        onSecondaryAction = onExit,
    )
}

/**
 * The card over an episode's last frame: the next episode, the end of the show, or why nothing
 * follows. Its first button takes focus, so OK does the expected thing: play on, or leave.
 */
@Composable
private fun EpisodeEndCard(
    card: EndCard,
    onPlayNext: () -> Unit,
    onWatchAgain: () -> Unit,
    onKeepWatching: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (card) {
        is EndCard.Countdown -> NextEpisodeCard(
            headline = "Next episode in ${card.secondsLeft} s",
            title = "${card.next.code} · ${card.next.displayName}",
            primaryLabel = "▶ Play now",
            onPrimary = onPlayNext,
            secondaryLabel = "Back",
            onSecondary = onBack,
            modifier = modifier,
        )

        is EndCard.Next -> NextEpisodeCard(
            headline = "Next episode",
            title = "${card.next.code} · ${card.next.displayName}",
            primaryLabel = "▶ Play",
            onPrimary = onPlayNext,
            secondaryLabel = if (card.midEpisode) "Keep watching" else "Back",
            onSecondary = if (card.midEpisode) onKeepWatching else onBack,
            modifier = modifier,
        )

        is EndCard.EndOfShow -> {
            val first = card.first
            NextEpisodeCard(
                headline = "You've reached the end",
                title = card.showTitle.takeIf { it.isNotBlank() },
                primaryLabel = "Back",
                onPrimary = onBack,
                secondaryLabel = first?.let { "Watch again from ${it.code}" },
                onSecondary = if (first != null) onWatchAgain else null,
                modifier = modifier,
            )
        }

        is EndCard.NextUnavailable -> NextEpisodeCard(
            headline = "${card.next.code} isn't available",
            title = card.next.displayName,
            detail = "The catalogue has no source for it yet.",
            primaryLabel = "Back",
            onPrimary = onBack,
            modifier = modifier,
        )

        EndCard.BackToShow -> NextEpisodeCard(
            headline = "That's the end of this episode",
            title = null,
            primaryLabel = "Back",
            onPrimary = onBack,
            modifier = modifier,
        )
    }
}

@Composable
private fun StillWatchingOverlay(onContinue: () -> Unit, onStop: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(TorfilxColors.ScrimStrong),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = "Are you still watching?",
                style = MaterialTheme.typography.headlineLarge,
                color = TorfilxColors.TextPrimary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvButton(text = "Continue watching", onClick = onContinue, autoFocus = true)
                TvButton(text = "Stop", onClick = onStop, primary = false)
            }
        }
    }
}

@Composable
private fun PlayerControls(
    state: PlayerUiState,
    pendingSeekMs: Long?,
    seekStepMs: Long,
    onPlayPause: () -> Unit,
    onNudgeSeek: (Long) -> Unit,
    onRestart: () -> Unit,
    onSelectAudio: (String) -> Unit,
    onSelectSubtitle: (String?) -> Unit,
    onAspect: (AspectMode) -> Unit,
    onSpeed: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayPosition = pendingSeekMs ?: state.positionMs
    // The scrubber takes focus when the overlay opens, so ←/→ scrub straight away — matching the
    // muscle memory from when the overlay is hidden. Pressing ↓ moves down to the button row.
    val scrubberFocus = remember { FocusRequester() }
    // Retried for the same reason the root's restore is: a single silent failure here leaves the
    // overlay on screen with nothing focused, and an overlay with no focus swallows the remote
    // entirely — press anything and the app appears frozen.
    LaunchedEffect(Unit) {
        repeat(FOCUS_RESTORE_ATTEMPTS) { attempt ->
            if (runCatching { scrubberFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(FOCUS_RESTORE_RETRY_MS * (attempt + 1))
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(TorfilxColors.Transparent, TorfilxColors.ScrimStrong),
                ),
            )
            .padding(horizontal = 48.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = state.title,
            style = MaterialTheme.typography.titleLarge,
            color = TorfilxColors.TextPrimary,
        )
        state.subtitle?.let { subtitle ->
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelLarge,
                color = TorfilxColors.TextSecondary,
            )
        }

        ScrubBar(
            positionMs = displayPosition,
            bufferedMs = state.bufferedPositionMs,
            durationMs = state.durationMs,
            isScrubbing = pendingSeekMs != null,
            focusRequester = scrubberFocus,
            stepMs = seekStepMs,
            onNudgeSeek = onNudgeSeek,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = Format.timecode(displayPosition),
                style = MaterialTheme.typography.labelLarge,
                color = TorfilxColors.TextSecondary,
            )
            Text(
                text = "-${Format.timecode((state.durationMs - displayPosition).coerceAtLeast(0))}",
                style = MaterialTheme.typography.labelLarge,
                color = TorfilxColors.TextSecondary,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(
                text = if (state.isPlaying) "❚❚ Pause" else "▶ Play",
                onClick = onPlayPause,
            )
            TvButton(text = "↺ Restart", onClick = onRestart, primary = false)
            TvButton(
                text = "Aspect: ${state.aspectMode.label}",
                onClick = { onAspect(state.aspectMode.next()) },
                primary = false,
            )
            TvButton(
                text = "Speed ${state.playbackSpeed}×",
                onClick = { onSpeed(state.playbackSpeed.nextSpeed()) },
                primary = false,
            )
        }

        if (state.audioTracks.isNotEmpty() || state.subtitleTracks.isNotEmpty()) {
            TrackPickers(
                state = state,
                onSelectAudio = onSelectAudio,
                onSelectSubtitle = onSelectSubtitle,
            )
        }
    }
}

@Composable
private fun TrackPickers(
    state: PlayerUiState,
    onSelectAudio: (String) -> Unit,
    onSelectSubtitle: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.audioTracks.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Audio",
                    style = MaterialTheme.typography.labelLarge,
                    color = TorfilxColors.TextSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
                state.audioTracks.forEach { track ->
                    TvChip(
                        text = track.label,
                        selected = track.isSelected,
                        onClick = { if (track.isSelectable) onSelectAudio(track.id) },
                    )
                }
            }
        }
        if (state.subtitleTracks.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Subtitles",
                    style = MaterialTheme.typography.labelLarge,
                    color = TorfilxColors.TextSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
                TvChip(
                    text = "Off",
                    selected = !state.subtitlesEnabled,
                    onClick = { onSelectSubtitle(null) },
                )
                state.subtitleTracks.forEach { track ->
                    TvChip(
                        // Unselectable tracks say why, rather than silently doing nothing.
                        text = if (track.isSelectable) track.label else "${track.label} (unavailable)",
                        selected = track.isSelected,
                        onClick = { if (track.isSelectable) onSelectSubtitle(track.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ScrubBar(
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    isScrubbing: Boolean,
    focusRequester: FocusRequester,
    stepMs: Long,
    onNudgeSeek: (Long) -> Unit,
) {
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val bufferedFraction = if (durationMs > 0) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    var focused by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxWidth()
            // Taller when focused so the scrubber is clearly the active control.
            .height(if (focused) 12.dp else 6.dp)
            .background(TorfilxColors.ProgressTrack)
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            // Announce the seek bar and position for VoiceView; left/right scrub when focused.
            .semantics {
                contentDescription = "Seek bar"
                stateDescription =
                    "${Format.timecode(positionMs)} of ${Format.timecode(durationMs)}"
            }
            .focusable()
            .onRemoteKey { key, isKeyDown, _ ->
                if (!isKeyDown) return@onRemoteKey false
                when (key) {
                    RemoteKeys.LEFT -> { onNudgeSeek(-stepMs); true }
                    RemoteKeys.RIGHT -> { onNudgeSeek(stepMs); true }
                    else -> false // ↑/↓ move focus to the buttons; OK/Back handled by the root
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxWidth(bufferedFraction)
                .fillMaxHeight()
                .background(TorfilxColors.SurfaceHighest),
        )
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .background(
                    when {
                        isScrubbing || focused -> TorfilxColors.Focus
                        else -> TorfilxColors.ProgressFill
                    },
                ),
        )
    }
}

@Composable
private fun KeepScreenOn(active: Boolean) {
    val context = LocalContext.current
    DisposableEffect(active) {
        val window = context.findActivity()?.window
        if (active) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

/** A TV video app pauses when it loses the screen — it never plays invisibly (plan.md §7.6). */
@Composable
private fun PauseWhenBackgrounded(onBackground: () -> Unit, onForeground: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> onBackground()
                Lifecycle.Event.ON_START -> onForeground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

private fun android.content.Context.findActivity(): Activity? {
    var context: android.content.Context? = this
    while (context is android.content.ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

private fun AspectMode.next(): AspectMode = when (this) {
    AspectMode.FIT -> AspectMode.FILL
    AspectMode.FILL -> AspectMode.ZOOM
    AspectMode.ZOOM -> AspectMode.FIT
}

private fun Float.nextSpeed(): Float = when (this) {
    1f -> 1.25f
    1.25f -> 1.5f
    1.5f -> 2f
    2f -> 0.5f
    0.5f -> 0.75f
    else -> 1f
}

/** Rewind and fast-forward jump this many ←/→ steps at once. With the default 10 s step, 30 s as before. */
private const val LONG_SEEK_MULTIPLIER = 3

private fun PlaybackError.isRetryable(): Boolean = when (this) {
    is PlaybackError.Unsupported, is PlaybackError.SharingNotEnabled -> false
    is PlaybackError.Network, is PlaybackError.NotFound, is PlaybackError.Unauthorized,
    is PlaybackError.OutOfSpace, is PlaybackError.Unknown,
    -> true
}
