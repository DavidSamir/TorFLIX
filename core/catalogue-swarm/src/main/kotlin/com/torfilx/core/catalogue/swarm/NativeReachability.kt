package com.torfilx.core.catalogue.swarm

/**
 * Keeps libtorrent4j wrappers alive for as long as native code still uses what they own.
 *
 * libtorrent4j frees a native object when its Java wrapper is garbage-collected. Several of its objects
 * point into memory that another wrapper owns without holding on to that owner: a torrent's info hash
 * points into the torrent info, and libtorrent 1.2's torrent creator reads a file list it only keeps a
 * reference to. Once compiled, Java treats a local variable as dead after its last use, so the owner can
 * be collected, and its memory freed, while the dependent object is still being read. On a desktop JVM
 * that takes the whole process down.
 */
internal object NativeReachability {

    /**
     * Keeps [owner] reachable up to this call. Call it after the last use of anything that points into
     * [owner].
     *
     * `java.lang.ref.Reference.reachabilityFence` does the same, but it needs Android 9 and this code
     * also runs on Fire OS 5. Entering the object's monitor needs the reference at that point, which is
     * what keeps the object alive until then.
     */
    @JvmStatic
    fun fence(owner: Any) {
        synchronized(owner) {
            // Intentionally empty: taking the monitor is the whole point.
        }
    }
}
