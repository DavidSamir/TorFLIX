package com.torfilx.core.catalogue.swarm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the puncher tracks. Its network side runs in the multi-process tests (SwarmNetworkTest). */
class HolePuncherTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `tracks info hashes case-insensitively, once each`() {
        val puncher = HolePuncher(session = { null }, scope = scope)
        puncher.track("ABCDEF0123456789ABCDEF0123456789ABCDEF01")
        puncher.track("abcdef0123456789abcdef0123456789abcdef01")
        assertEquals(setOf("abcdef0123456789abcdef0123456789abcdef01"), puncher.trackedHashes())
    }

    @Test
    fun `untracks one torrent or all of them`() {
        val puncher = HolePuncher(session = { null }, scope = scope)
        puncher.track("1111111111111111111111111111111111111111")
        puncher.track("2222222222222222222222222222222222222222")
        puncher.untrack("1111111111111111111111111111111111111111")
        assertEquals(setOf("2222222222222222222222222222222222222222"), puncher.trackedHashes())
        puncher.untrackAll()
        assertTrue(puncher.trackedHashes().isEmpty())
    }

    @Test
    fun `idles without a session instead of failing`() {
        val puncher = HolePuncher(session = { null }, scope = scope)
        puncher.track("3333333333333333333333333333333333333333")
        Thread.sleep(300)
        assertTrue(scope.coroutineContext[kotlinx.coroutines.Job]!!.isActive)
    }
}
