package com.torfilx.tools.catalog

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ArgsTest {

    @Test
    fun `a command, options, repeated options and flags are all understood`() {
        val args = Args.parse(
            listOf("build", "--catalog", "a.json", "--version=7", "--tracker", "udp://x", "--tracker", "udp://y", "--private"),
        )

        assertThat(args.command).isEqualTo("build")
        assertThat(args.required("catalog")).isEqualTo("a.json")
        assertThat(args.long("version")).isEqualTo(7)
        assertThat(args.values("tracker")).containsExactly("udp://x", "udp://y").inOrder()
        assertThat(args.flag("private")).isTrue()
        assertThat(args.flag("force")).isFalse()
        assertThat(args.value("missing")).isNull()
    }

    @Test
    fun `mistakes are reported rather than guessed at`() {
        assertThrows(UsageException::class.java) { Args.parse(listOf("build", "stray")) }
        assertThrows(UsageException::class.java) { Args.parse(listOf("build", "--catalog")) }
        assertThrows(UsageException::class.java) { Args.parse(listOf("build", "--catalog", "--version", "3")) }
        assertThrows(UsageException::class.java) { Args.parse(listOf("build")).required("catalog") }
        assertThrows(UsageException::class.java) { Args.parse(listOf("build", "--version", "seven")).long("version") }
    }

    @Test
    fun `help is a command of its own`() {
        assertThat(Args.parse(listOf("build", "--help")).command).isEqualTo("help")
        assertThat(Args.parse(emptyList()).command).isNull()
    }

    @Test
    fun `endpoints parse with or without brackets`() {
        assertThat(parseEndpoint("127.0.0.1:6881").port).isEqualTo(6881)
        assertThat(parseEndpoint("127.0.0.1:6881").hostString).isEqualTo("127.0.0.1")
        assertThat(parseEndpoint("[::1]:51413").hostString).isEqualTo("::1")
        assertThrows(UsageException::class.java) { parseEndpoint("localhost") }
        assertThrows(UsageException::class.java) { parseEndpoint("localhost:0") }
        assertThrows(UsageException::class.java) { parseEndpoint("localhost:99999") }
    }
}
