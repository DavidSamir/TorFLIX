package com.torfilx.core.catalogue.swarm

import org.libtorrent4j.LibTorrent

/** Whether libtorrent's native library could be loaded in this process, and why not when it could not. */
object LibtorrentNative {

    private val probe: Result<String> by lazy { runCatching { LibTorrent.version() } }

    /** The native libtorrent version, or null when the library did not load. */
    val version: String? get() = probe.getOrNull()

    /** The failure to load, or null when the library is usable. */
    val loadError: Throwable? get() = probe.exceptionOrNull()

    val isAvailable: Boolean get() = probe.isSuccess
}
