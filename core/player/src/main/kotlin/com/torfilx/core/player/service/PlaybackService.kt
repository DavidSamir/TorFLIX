package com.torfilx.core.player.service

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.torfilx.core.common.di.ApplicationScope
import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.core.data.torrent.TorrentCoordinator
import com.torfilx.core.player.PlaybackController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "PlaybackService"

/**
 * Publishes the player as a `MediaSession`.
 *
 * This is what makes the remote's transport keys **and Alexa** ("Alexa, pause", "rewind 30 seconds")
 * control playback: on Fire TV those commands are delivered to the active media session, not to the
 * foreground activity (plan.md §7.1).
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject
    lateinit var playbackController: PlaybackController

    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    @Inject
    lateinit var torrentCoordinator: TorrentCoordinator

    private var mediaSession: MediaSession? = null

    /** The player the session currently wraps, so a rebuilt player is noticed. */
    private var boundPlayer: Player? = null

    override fun onCreate() {
        super.onCreate()
        runCatching {
            val player = playbackController.ensurePlayer()
            boundPlayer = player
            mediaSession = MediaSession.Builder(this, sessionPlayer(player)).build()
            TorfilxLog.i(TAG, "Media session created")
        }.onFailure { TorfilxLog.e(TAG, "Could not create media session", it) }

        // The player can be rebuilt when a playback setting changes (tunneling, software decoding).
        // Re-point the session at the current instance so remote and Alexa keys never drive a dead
        // player.
        playbackController.playerFlow
            .onEach { player ->
                if (player != null && boundPlayer !== player) {
                    boundPlayer = player
                    runCatching { mediaSession?.player = sessionPlayer(player) }
                        .onFailure { TorfilxLog.w(TAG, "Could not rebind media session player", it) }
                }
            }
            .launchIn(appScope)
    }

    /**
     * The player as the session presents it: unchanged, except that "next" always exists and means the
     * next episode. The player holds one item at a time, so left alone the session would report no next
     * item and drop the remote's next key and "Alexa, next" on the floor.
     */
    @OptIn(UnstableApi::class)
    private fun sessionPlayer(player: Player): Player = object : ForwardingPlayer(player) {
        override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .build()

        override fun isCommandAvailable(command: Int): Boolean =
            command == Player.COMMAND_SEEK_TO_NEXT ||
                command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ||
                super.isCommandAvailable(command)

        override fun seekToNext() = playbackController.onMediaNext()

        override fun seekToNextMediaItem() = playbackController.onMediaNext()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * The app was swiped away from the launcher's recents. A TV video app should not keep playing
     * invisibly, so playback stops — and so does the torrent session, so nothing keeps uploading to
     * the swarm (and exposing the viewer's IP) after they have left the app (plan.md §7.6).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        playbackController.stop(release = false)
        stopTorrentSession()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // The service going away is the one genuine teardown: release the reused player and stop the
        // torrent session so no seeding continues in the background without a foreground component.
        playbackController.stop(release = true)
        stopTorrentSession()
        mediaSession?.run {
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    /** Best-effort stop of the BitTorrent session; the process may be reaped before it finishes. */
    private fun stopTorrentSession() {
        appScope.launch { runCatching { torrentCoordinator.shutdown() } }
    }
}
