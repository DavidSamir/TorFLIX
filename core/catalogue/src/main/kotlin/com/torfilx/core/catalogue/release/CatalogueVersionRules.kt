package com.torfilx.core.catalogue.release

/** The ordering rules between catalogue releases, in one place so every caller agrees. */
object CatalogueVersionRules {

    /** A release replaces what is installed only when it is strictly newer. */
    fun isNewer(candidate: Long, installed: Long): Boolean = candidate > installed

    /**
     * A fetched release is used instead of the bundled one only when strictly newer.
     *
     * On a tie the bundled copy wins: an app update whose bundled catalogue has caught up with the
     * swarm should stop depending on files it downloaded earlier.
     */
    fun preferFetched(fetched: Long, bundled: Long): Boolean = fetched > bundled
}
