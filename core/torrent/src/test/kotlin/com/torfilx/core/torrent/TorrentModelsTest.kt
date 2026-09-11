package com.torfilx.core.torrent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StorageBudgetTest {

    private val gb = 1024L * 1024 * 1024

    @Test
    fun `budget is a share of free space after the reserve`() {
        val budget = StorageBudget(fractionOfFree = 0.5f, maxBytes = 100 * gb, reserveBytes = 500L * 1024 * 1024)
        // 4 GB free, keep 500 MB back, use half of the rest.
        val cap = budget.capBytes(4 * gb)
        assertThat(cap).isEqualTo(((4 * gb - 500L * 1024 * 1024) * 0.5).toLong())
    }

    @Test
    fun `an almost-full device gets a zero budget instead of a negative one`() {
        val budget = StorageBudget()
        assertThat(budget.capBytes(100L * 1024 * 1024)).isEqualTo(0L)
        assertThat(budget.capBytes(0L)).isEqualTo(0L)
    }

    @Test
    fun `the absolute maximum wins on a large disk`() {
        val budget = StorageBudget(fractionOfFree = 0.5f, maxBytes = 2 * gb)
        assertThat(budget.capBytes(500 * gb)).isEqualTo(2 * gb)
    }

    @Test
    fun `default budget keeps half of free space for the user`() {
        val budget = StorageBudget.DEFAULT
        val free = 10 * gb
        assertThat(budget.capBytes(free)).isLessThan(free / 2 + 1)
    }
}
