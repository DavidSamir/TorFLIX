package com.torfilx.core.catalogue.swarm

/**
 * Where the swarm code reports what it is doing.
 *
 * An interface rather than a logger dependency because this module runs in two very different
 * places: the app routes it into its exportable ring-buffer log, the publisher tool prints it.
 */
fun interface SwarmLog {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    fun log(level: Level, message: String, error: Throwable?)

    fun debug(message: String) = log(Level.DEBUG, message, null)

    fun info(message: String) = log(Level.INFO, message, null)

    fun warn(message: String, error: Throwable? = null) = log(Level.WARN, message, error)

    fun error(message: String, error: Throwable? = null) = log(Level.ERROR, message, error)

    companion object {
        val NONE: SwarmLog = SwarmLog { _, _, _ -> }

        /** Prints to standard error with a level prefix; used by the publisher tool and tests. */
        val STDERR: SwarmLog = SwarmLog { level, message, error ->
            System.err.println("${level.name.padEnd(LEVEL_WIDTH)} $message")
            error?.printStackTrace()
        }

        private const val LEVEL_WIDTH = 5
    }
}
