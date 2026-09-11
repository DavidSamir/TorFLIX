package com.torfilx.core.catalogue.swarm

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SessionShutdownTest {

    @Test
    fun `a stop that finishes in time reports so and leaves nothing pending`() {
        val shutdown = SessionShutdown()
        var ran = false

        assertThat(shutdown.stopWithin(5_000) { ran = true }).isTrue()

        assertThat(ran).isTrue()
        assertThat(shutdown.isStopping).isFalse()
        assertThat(shutdown.awaitPending(1)).isTrue()
    }

    @Test
    fun `a stop that throws has still finished, and the error is handed over`() {
        val shutdown = SessionShutdown()
        var reported: Exception? = null

        val finished = shutdown.stopWithin(5_000, onError = { reported = it }) { error("native stop failed") }

        assertThat(finished).isTrue()
        assertThat(reported).hasMessageThat().isEqualTo("native stop failed")
        assertThat(shutdown.isStopping).isFalse()
    }

    @Test
    fun `a hung stop is given up on, stays visible, and is noticed once it finally ends`() {
        val shutdown = SessionShutdown()
        val release = CountDownLatch(1)

        assertThat(shutdown.stopWithin(100) { release.await(30, TimeUnit.SECONDS) }).isFalse()

        assertThat(shutdown.isStopping).isTrue()
        assertThat(shutdown.awaitPending(50)).isFalse()

        release.countDown()
        assertThat(shutdown.awaitPending(5_000)).isTrue()
        assertThat(shutdown.isStopping).isFalse()
    }

    @Test
    fun `the stop runs on a named daemon thread, so a hung one never keeps a process alive`() {
        val shutdown = SessionShutdown("test-session-stop")
        var name: String? = null
        var daemon: Boolean? = null

        shutdown.stopWithin(5_000) {
            name = Thread.currentThread().name
            daemon = Thread.currentThread().isDaemon
        }

        assertThat(name).isEqualTo("test-session-stop")
        assertThat(daemon).isTrue()
    }
}
