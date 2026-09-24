package com.torfilx.tv

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.core.data.settings.SettingsRepository
import com.torfilx.core.player.PlaybackController
import com.torfilx.core.player.display.DisplayModeController
import com.torfilx.core.player.service.PlaybackService
import com.torfilx.core.ui.theme.TorfilxColors
import com.torfilx.core.ui.theme.TorfilxTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

private const val TAG = "MainActivity"

/**
 * The single activity.
 *
 * `singleTask` + a single activity is what keeps focus state and the back stack coherent on a TV,
 * where the user re-enters the app from the launcher constantly (plan.md §2.3, §3).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var playbackController: PlaybackController

    @Inject
    lateinit var displayModeController: DisplayModeController

    @Inject
    lateinit var torrentCoordinator: com.torfilx.core.data.torrent.TorrentCoordinator

    @Inject
    @com.torfilx.core.common.di.ApplicationScope
    lateinit var applicationScope: kotlinx.coroutines.CoroutineScope

    private val notificationPermission =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
            // Result ignored: without it the media notification is merely hidden; the foreground
            // service and playback still run.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        TorfilxLog.debugEnabled = BuildConfig.DEBUG

        // Collected on IO: the settings store must never be opened on the main thread during
        // onCreate (see SettingsRepository). The first frame draws with motion on, then follows the
        // stored choice a moment later.
        val reduceMotionFlow = settingsRepository.reduceMotion.flowOn(Dispatchers.IO)
        val askSharingFlow = settingsRepository.askSharingAtLaunch.flowOn(Dispatchers.IO)
        setContent {
            val reduceMotion by reduceMotionFlow.collectAsState(initial = false)
            // Null until the stored answer is read, so neither the app nor the question flashes up first.
            // True until sharing is accepted: the app itself is only shown to a viewer who has.
            val askSharingState by askSharingFlow.collectAsState<Boolean, Boolean?>(initial = null)
            val askSharing = askSharingState
            TorfilxTheme(reduceMotion = reduceMotion) {
                when {
                    askSharing == null -> Box(Modifier.fillMaxSize().background(TorfilxColors.Background))
                    askSharing && torrentCoordinator.isAvailable() -> FirstRunSharingPrompt(
                        onAccept = { applicationScope.launch { settingsRepository.acceptSharingAtLaunch() } },
                        onExit = { finish() },
                    )
                    else -> TorfilxApp(onExitApp = { finish() })
                }
            }
        }

        requestNotificationPermissionIfNeeded()
    }

    /**
     * Media3 promotes the playback service to the foreground and posts the media notification on its
     * own. From Android 13 (API 33) that notification also needs the POST_NOTIFICATIONS runtime
     * grant to be visible — no current Fire TV runs API 33, but targetSdk is 34, so request it where
     * it applies. It is best-effort: denial only hides the notification, it does not stop playback.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            runCatching { notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    override fun onStart() {
        super.onStart()
        displayModeController.attach(this)
        // The media session must exist while the app is in the foreground so the remote's transport
        // keys and Alexa reach the player (plan.md §7.1).
        runCatching { startService(Intent(this, PlaybackService::class.java)) }
            .onFailure { TorfilxLog.w(TAG, "Could not start playback service", it) }
        // Exiting stops the torrent session, and a relaunch in the same process starts no new one by
        // itself: warm it now, so the first play does not start against a cold DHT.
        torrentCoordinator.warmIfConsented()
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) {
            playbackController.stop(release = true)
            // Leaving the app for good also stops the torrent session, so nothing keeps seeding
            // (uploading, and exposing the viewer's IP) in the background after they are gone.
            applicationScope.launch { runCatching { torrentCoordinator.shutdown() } }
        }
        // Always hand the TV back its original display mode when we lose the screen.
        displayModeController.detach(this)
    }

    /**
     * Media keys are handled by the MediaSession, but Fire TV also delivers them here when no
     * session is active — forwarding them keeps play/pause working on Home.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (playbackController.player != null) {
                    playbackController.togglePlayPause()
                    true
                } else {
                    super.onKeyDown(keyCode, event)
                }
            }

            // Next track: offer the next episode (or play it from its card). Handled here while the app
            // is in front; the media session carries it otherwise, and for "Alexa, next".
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (playbackController.player != null) {
                    playbackController.onMediaNext()
                    true
                } else {
                    super.onKeyDown(keyCode, event)
                }
            }

            else -> super.onKeyDown(keyCode, event)
        }
    }
}
