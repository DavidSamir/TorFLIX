package com.torfilx.core.player

import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.core.model.FileSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val TAG = "Playback"

/**
 * Starts the next episode's torrent while the end-of-episode countdown runs, so that when the
 * countdown ends the swarm has usually been found and the first pieces are already arriving.
 *
 * A new swarm takes ten to sixty seconds to resolve; a countdown that ends in a spinner feels broken.
 * Only a countdown warms anything: the viewer asked for autoplay, so the next episode is the one they
 * will almost certainly watch. The plain "next episode" card (autoplay off, or off screen) warms
 * nothing, because nothing may join a swarm the viewer has not chosen.
 *
 * One fetch at a time. It is never cancelled part-way: the engine adds a torrent to its session the
 * moment it is asked, and a cancelled fetch would leave it there unmanaged — downloading every file,
 * invisible to the storage budget. So a fetch always runs to its end and is then either handed over
 * to the episode being opened ([claim]) or released ([coolDown]).
 *
 * @param resolve what to fetch for an episode: its chosen torrent source, or null when there is
 *   nothing to warm (no source, not a torrent).
 * @param isPlaying true for the torrent feeding the player now. A season pack's next episode is in the
 *   same torrent; asking the engine for it would switch the file under the player, and there is no
 *   swarm to find anyway.
 * @param stream starts streaming [Target] and returns once its metadata has arrived.
 * @param release a fetched torrent is not wanted after all: it stops streaming, and seeds or goes
 *   as the viewer's setting says.
 * @param discard a fetch failed part-way: whatever it added to the session goes, data included.
 */
internal class NextEpisodeWarmer(
    private val scope: CoroutineScope,
    private val resolve: suspend (playableId: String) -> Target?,
    private val isPlaying: (infoHash: String) -> Boolean,
    private val stream: suspend (Target) -> Unit,
    private val release: suspend (infoHash: String) -> Unit,
    private val discard: suspend (infoHash: String) -> Unit,
) {

    data class Target(
        val magnet: String,
        val infoHash: String,
        val selection: FileSelection,
        val displayName: String?,
    )

    private class Warm(val playableId: String) {
        lateinit var job: Job

        /** Known once the source is resolved; null until then, and when there was nothing to warm. */
        @Volatile var infoHash: String? = null

        /** The engine has it and is streaming it. */
        @Volatile var streamed: Boolean = false

        /** False once the viewer went elsewhere; whichever side sees both flags last releases it. */
        @Volatile var wanted: Boolean = true
    }

    private val lock = Any()
    private var current: Warm? = null

    /** The episode being warmed, for tests and logs. */
    val warmingId: String? get() = synchronized(lock) { current?.playableId }

    /** Starts fetching [playableId]'s torrent. Anything warmed before for another episode is released. */
    fun warm(playableId: String) {
        val warm = Warm(playableId)
        // The job exists before the warm is published, so a claim can never meet a warm without one;
        // it starts once the warm is current (a join from claim would start it too).
        warm.job = scope.launch(start = CoroutineStart.LAZY) {
            val target = runCatching { resolve(playableId) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
                ?: return@launch
            if (isPlaying(target.infoHash)) return@launch
            if (!warm.wanted) return@launch
            warm.infoHash = target.infoHash
            TorfilxLog.i(TAG, "Warming $playableId during the countdown")
            try {
                stream(target)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                TorfilxLog.w(TAG, "Could not warm $playableId; it will be fetched when it opens", error)
                warm.infoHash = null
                runCatching { discard(target.infoHash) }
                return@launch
            }
            warm.streamed = true
            if (!warm.wanted) runCatching { release(target.infoHash) }
        }
        val previous = synchronized(lock) {
            current?.takeIf { it.playableId == playableId }?.let {
                warm.job.cancel()
                return
            }
            current.also { current = warm }
        }
        previous?.let(::letGo)
        warm.job.start()
    }

    /**
     * The viewer backed out, or the app left the screen: whatever was warmed is released — now if it
     * has arrived, or the moment it does.
     */
    fun coolDown() {
        val warm = synchronized(lock) { current.also { current = null } } ?: return
        letGo(warm)
    }

    /**
     * [playableId] is opening. When it is the episode being warmed, waits for that fetch to finish and
     * hands the torrent over: the open's own request then finds it already in the engine. Anything
     * warmed for another episode is released.
     *
     * @return the info hash handed over, or null. The caller releases it if the open ends up playing a
     *   different torrent (another quality chosen by hand, or a failure before streaming).
     */
    suspend fun claim(playableId: String): String? {
        val warm = synchronized(lock) { current.also { current = null } } ?: return null
        if (warm.playableId != playableId) {
            letGo(warm)
            return null
        }
        warm.job.join()
        return warm.infoHash?.takeIf { warm.streamed }
    }

    private fun letGo(warm: Warm) {
        warm.wanted = false
        val infoHash = warm.infoHash ?: return
        if (warm.streamed) scope.launch { runCatching { release(infoHash) } }
    }
}
