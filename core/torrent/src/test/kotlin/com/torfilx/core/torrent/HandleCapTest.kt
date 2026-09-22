package com.torfilx.core.torrent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HandleCapTest {

    private fun seeding(n: Int, touched: Long = n.toLong()) = HandleCap.Entry("seed-$n", touched, isStreaming = false)
    private fun streaming(n: Int, touched: Long = n.toLong()) = HandleCap.Entry("play-$n", touched, isStreaming = true)

    @Test
    fun `within the cap nothing goes`() {
        assertThat(HandleCap.surplus((1..8).map { seeding(it) })).isEmpty()
    }

    @Test
    fun `beyond the cap the oldest-touched go first`() {
        val entries = listOf(seeding(5), seeding(1), seeding(9), seeding(3)) + (10..15).map { seeding(it) }
        assertThat(HandleCap.surplus(entries)).containsExactly("seed-1", "seed-3").inOrder()
    }

    @Test
    fun `a streaming torrent is never chosen, however old`() {
        val entries = listOf(streaming(0), streaming(1)) + (2..9).map { seeding(it) }
        assertThat(HandleCap.surplus(entries)).containsExactly("seed-2", "seed-3").inOrder()
    }

    @Test
    fun `when everything over the cap is streaming the session stays over it`() {
        val entries = (1..9).map { streaming(it) }
        assertThat(HandleCap.surplus(entries)).isEmpty()
    }
}
