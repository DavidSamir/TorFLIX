package com.torfilx.core.catalogue.swarm

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Stops a libtorrent session without letting a hung shutdown take its caller down with it.
 *
 * libtorrent 1.2's session destructor waits for its network thread to finish every outstanding
 * operation, and now and then that never happens: a `fetch` that had finished its work was seen
 * sitting in the native destructor for more than ten minutes. [stopWithin] runs the stop on a thread
 * of its own and waits a bounded time. A caller that gives up can carry on: a command-line tool exits,
 * which ends the stuck thread with the process, and the app refuses to start a new session until the
 * old one has really gone (see [awaitPending]).
 */
class SessionShutdown(private val threadName: String = DEFAULT_THREAD_NAME) {

    private val pending = AtomicReference<Thread?>(null)

    /** True while a stop that overran its wait is still running. */
    val isStopping: Boolean get() = pending.get()?.isAlive == true

    /**
     * Runs [stop] on a daemon thread and waits for it at most [timeoutMs].
     *
     * A stop that throws has finished; the exception goes to [onError].
     *
     * @return true when the stop finished within the wait.
     */
    fun stopWithin(timeoutMs: Long, onError: (Exception) -> Unit = {}, stop: () -> Unit): Boolean {
        val finished = CountDownLatch(1)
        val thread = Thread(
            {
                try {
                    stop()
                } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                    onError(error)
                } finally {
                    finished.countDown()
                }
            },
            threadName,
        ).apply { isDaemon = true }
        pending.set(thread)
        thread.start()
        val inTime = finished.await(timeoutMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        if (inTime) pending.compareAndSet(thread, null)
        return inTime
    }

    /**
     * Waits at most [timeoutMs] for a stop that overran [stopWithin].
     *
     * @return true when no stop is still running.
     */
    fun awaitPending(timeoutMs: Long): Boolean {
        val thread = pending.get() ?: return true
        thread.join(timeoutMs.coerceAtLeast(1))
        if (thread.isAlive) return false
        pending.compareAndSet(thread, null)
        return true
    }

    companion object {
        const val DEFAULT_THREAD_NAME = "libtorrent-session-stop"
    }
}
