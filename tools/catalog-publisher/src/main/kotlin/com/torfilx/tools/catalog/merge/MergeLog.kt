package com.torfilx.tools.catalog.merge

import java.util.EnumMap

/** How much a finding of the merge matters. */
enum class Severity(val label: String) {
    /** The run cannot produce a catalogue it can vouch for. Nothing is written, built or published. */
    ERROR("error"),

    /** Something in the input was wrong and was skipped or repaired. Fails the run under `--strict`. */
    WARNING("warning"),

    /** What the merge did, for the record. */
    NOTE("note"),
}

/**
 * Everything the merge noticed, in the order it noticed it.
 *
 * Each finding goes to [details] at once, so a run over a folder of a million files never holds its
 * report in memory; the first [KEEP] of each severity are also kept for the console. A finding that
 * would repeat for every title, such as a field the format does not know, is tallied instead and
 * written once, with its count, by [closeTallies].
 */
class MergeLog(private val details: Appendable, val strict: Boolean = false) {

    private val counts = EnumMap<Severity, Int>(Severity::class.java)
    private val firsts = EnumMap<Severity, MutableList<String>>(Severity::class.java)
    private val tallies = LinkedHashMap<Pair<Severity, String>, Tally>()

    private class Tally(val firstWhere: String) {
        var count = 0
    }

    fun error(where: String, message: String) = add(Severity.ERROR, where, message)

    fun warning(where: String, message: String) = add(Severity.WARNING, where, message)

    fun note(where: String, message: String) = add(Severity.NOTE, where, message)

    fun add(severity: Severity, where: String, message: String) {
        val line = if (where.isEmpty()) message else "$where: $message"
        counts[severity] = count(severity) + 1
        val kept = firsts.getOrPut(severity) { ArrayList() }
        if (kept.size < KEEP) kept += line
        details.append(severity.label.padEnd(LABEL_WIDTH)).append(line).append('\n')
    }

    /** A line of the audit trail that is not a finding: which file is being read, what it held. */
    fun record(line: String) {
        details.append(line).append('\n')
    }

    /** Counts one occurrence of [what]; [closeTallies] reports it once, with the count. */
    fun tally(severity: Severity, what: String, where: String) {
        tallies.getOrPut(severity to what) { Tally(where) }.count++
    }

    /** Reports every tallied finding, once each. */
    fun closeTallies() {
        val closing = tallies.toList()
        tallies.clear()
        closing.forEach { (key, tally) ->
            val times = if (tally.count == 1) "once" else "${tally.count} times"
            add(key.first, "", "${key.second} ($times, first at ${tally.firstWhere})")
        }
    }

    fun count(severity: Severity): Int = counts[severity] ?: 0

    fun first(severity: Severity): List<String> = firsts[severity].orEmpty()

    /** True when the run must stop: any error, or any warning under `--strict`. */
    val failed: Boolean get() = count(Severity.ERROR) > 0 || (strict && count(Severity.WARNING) > 0)

    companion object {
        /** Findings of each severity kept for the console; the report has all of them. */
        const val KEEP = 25
        private const val LABEL_WIDTH = 9
    }
}
