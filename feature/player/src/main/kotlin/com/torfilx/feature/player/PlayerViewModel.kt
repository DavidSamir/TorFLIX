package com.torfilx.feature.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torfilx.core.data.settings.SettingsRepository
import com.torfilx.core.model.AppSettings
import com.torfilx.core.player.AspectMode
import com.torfilx.core.player.PlaybackController
import com.torfilx.core.player.PlaybackRequest
import com.torfilx.core.player.PlayerUiState
import com.torfilx.core.player.display.DisplayModeController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Thin wrapper over [PlaybackController].
 *
 * The controller is a singleton because the same player is driven by the MediaSession; the ViewModel
 * exists to scope *screen* concerns — the overlay auto-hide timer and the debounced seek target.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val controller: PlaybackController,
    private val settingsRepository: SettingsRepository,
    private val displayModeController: DisplayModeController,
) : ViewModel() {

    private val playableId: String = requireNotNull(savedStateHandle.get<String>(ARG_PLAYABLE_ID))
    private val startPositionMs: Long? = savedStateHandle.get<Long>(ARG_START_POSITION)?.takeIf { it >= 0 }
    private val sourceId: String? = savedStateHandle.get<String>(ARG_SOURCE_ID)?.takeIf { it.isNotBlank() }

    val state: StateFlow<PlayerUiState> = controller.state

    private val _controlsVisible = MutableStateFlow(true)
    val controlsVisible: StateFlow<Boolean> = _controlsVisible.asStateFlow()

    /**
     * Increments on every interaction with the overlay, so the auto-hide countdown can restart.
     *
     * A plain `visible` flag is not enough to drive that: `showControls()` writes `true` over `true`,
     * a `StateFlow` does not re-emit an equal value, and the effect that owns the timer therefore
     * never restarted. The overlay vanished four seconds after it first appeared no matter how much
     * the viewer was pressing — which is what "the controls don't work properly" looked like.
     */
    private val _controlsRevision = MutableStateFlow(0L)
    val controlsRevision: StateFlow<Long> = _controlsRevision.asStateFlow()

    /** Timestamp of the last accepted seek nudge, for the repeat throttle. */
    private var lastNudgeAtMs: Long = 0L

    /** Non-null while the user is scrubbing: the timeline shows this, the player has not moved yet. */
    private val _pendingSeekMs = MutableStateFlow<Long?>(null)
    val pendingSeekMs: StateFlow<Long?> = _pendingSeekMs.asStateFlow()

    /** Observable so the video surface re-attaches whenever the player instance changes. */
    val playerFlow: StateFlow<androidx.media3.exoplayer.ExoPlayer?> = controller.playerFlow
    val currentFrameRate: Float? get() = controller.currentFrameRate

    private val _skipIntroAutomatically = MutableStateFlow(false)
    val skipIntroAutomatically: StateFlow<Boolean> = _skipIntroAutomatically.asStateFlow()

    /** Seek step, subtitle look and the stats overlay: what the screen itself needs from Settings. */
    val preferences: StateFlow<AppSettings> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    init {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            _skipIntroAutomatically.value = settings.skipIntroAutomatically

            controller.open(
                PlaybackRequest(
                    playableId = playableId,
                    startPositionMs = startPositionMs,
                    sourceId = sourceId,
                ),
            )

            // Frame-rate matching happens after the source is known (it carries the fps) and before
            // the first frame is visible (plan.md §7.3).
            if (settings.frameRateMatching) {
                val switching = displayModeController.applyForContent(
                    contentFrameRate = controller.currentFrameRate,
                    enabled = true,
                )
                if (switching) {
                    _displaySwitching.value = true
                    kotlinx.coroutines.delay(DISPLAY_SWITCH_SETTLE_MS)
                    _displaySwitching.value = false
                }
            }
        }
    }

    private val _displaySwitching = MutableStateFlow(false)

    /** True while the TV is resyncing HDMI after a mode switch; the screen stays black. */
    val displaySwitching: StateFlow<Boolean> = _displaySwitching.asStateFlow()

    fun showControls() {
        _controlsVisible.value = true
        _controlsRevision.value++
    }

    fun hideControls() {
        _controlsVisible.value = false
    }

    fun toggleControls() {
        _controlsVisible.value = !_controlsVisible.value
        _controlsRevision.value++
    }

    fun togglePlayPause() {
        controller.togglePlayPause()
        showControls()
    }

    /**
     * Accumulates a seek target instead of seeking per key press.
     *
     * Holding ← on the remote fires ~20 key events per second; seeking on each would thrash the
     * decoder. The screen commits the accumulated target after a short idle period (plan.md §7.4).
     */
    fun nudgeSeek(deltaMs: Long) {
        // Holding the key fires ~20 events a second. Accumulating a full 10-second step for each one
        // means a one-second press jumps over three minutes of film, which is impossible to aim with.
        // Throttling the *accepted* nudges keeps a held key scrubbing at a usable rate while a single
        // deliberate press — always far apart than this — is never dropped.
        val now = System.currentTimeMillis()
        if (now - lastNudgeAtMs < NUDGE_THROTTLE_MS) return
        lastNudgeAtMs = now

        val base = _pendingSeekMs.value ?: state.value.positionMs
        val duration = state.value.durationMs
        val target = (base + deltaMs).coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE)
        _pendingSeekMs.value = target
        showControls()
    }

    fun commitSeek() {
        val target = _pendingSeekMs.value ?: return
        _pendingSeekMs.value = null
        controller.seekTo(target)
    }

    fun seekTo(positionMs: Long) {
        _pendingSeekMs.value = null
        controller.seekTo(positionMs)
    }

    fun skipIntro() = controller.skipIntro()
    fun selectAudioTrack(id: String) = controller.selectAudioTrack(id)
    fun selectSubtitleTrack(id: String?) = controller.selectSubtitleTrack(id)
    fun setSpeed(speed: Float) = controller.setSpeed(speed)
    fun setAspectMode(mode: AspectMode) = controller.setAspectMode(mode)
    fun retry() = controller.retry()

    /**
     * Enables sharing from the error overlay and retries.
     *
     * With BitTorrent as the only delivery path, "sharing is off" is a dead end unless the viewer can
     * fix it where they hit it — so the consent decision is offered again right here.
     */
    fun enableSharingAndRetry() {
        viewModelScope.launch {
            settingsRepository.setSharingConsent(true)
            controller.retry()
        }
    }
    /**
     * Any remote key press. Keeps the overlay alive while the viewer is using it, and tells the
     * controller the viewer is present so "are you still watching?" does not fire at them.
     */
    fun noteUserInput() {
        controller.noteUserInput()
        if (_controlsVisible.value) _controlsRevision.value++
    }
    fun dismissStillWatching(continueWatching: Boolean) = controller.dismissStillWatching(continueWatching)

    /**
     * Called when the player screen is left: saves progress and stops playback, but keeps the
     * player instance alive for the next title. Releasing it here churned the Fire TV's single
     * video decoder and left later titles with sound but no picture; the reused player is only
     * torn down when the playback service is destroyed.
     */
    fun leavePlayer() {
        displayModeController.reset()
        controller.stop(release = false)
    }

    /**
     * Called when the app goes to the background: TV video apps pause rather than play blind, and a
     * next-episode countdown stops and waits for the viewer to come back.
     */
    fun onBackground() = controller.onBackground()

    fun onForeground() = controller.onForeground()

    /** "Play now" on the next-episode countdown, or "Play" on the next-episode card. */
    fun playNext() = controller.playNext()

    /** "Watch again" at the end of a show. */
    fun watchAgain() = controller.watchAgain()

    fun dismissNextEpisodeCard() = controller.dismissNextEpisodeCard()

    override fun onCleared() {
        displayModeController.reset()
        controller.stop(release = false)
        super.onCleared()
    }

    companion object {
        const val ARG_PLAYABLE_ID = "playableId"
        const val ARG_START_POSITION = "startPositionMs"
        const val ARG_SOURCE_ID = "sourceId"
        private const val DISPLAY_SWITCH_SETTLE_MS = 1_500L

        /** Minimum gap between accepted seek nudges while a direction key is held. */
        private const val NUDGE_THROTTLE_MS = 150L
    }
}
