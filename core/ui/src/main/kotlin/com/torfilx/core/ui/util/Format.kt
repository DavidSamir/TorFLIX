package com.torfilx.core.ui.util

import com.torfilx.core.model.MediaItem
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Display formatting.
 *
 * Kept free of Android resources so it is unit-testable; the few user-visible words ("left", "min")
 * are supplied by the caller from string resources where localisation matters.
 */
object Format {

    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60 * MINUTE_MS

    /** `2h 14m`, `48m`, `0m` — never an empty string. */
    fun runtime(ms: Long?): String {
        if (ms == null || ms <= 0) return ""
        val hours = ms / HOUR_MS
        val minutes = ((ms % HOUR_MS) + MINUTE_MS / 2) / MINUTE_MS // round to nearest minute
        val (h, m) = if (minutes == 60L) hours + 1 to 0L else hours to minutes
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            else -> "${m}m"
        }
    }

    /** `1:12:03` / `12:03` — used on the player timeline where every second matters. */
    fun timecode(ms: Long): String {
        val safe = ms.coerceAtLeast(0)
        val totalSeconds = safe / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    /**
     * Meta line for details/hero: `2024 · 2h 14m · 16+ · Action, Sci-Fi` for a film, and
     * `1959 · 5 seasons · Sci-Fi, Drama` for a show, whose length is its seasons, not a runtime.
     */
    fun metaLine(item: MediaItem, maxGenres: Int = 2): String = buildList {
        item.year?.let { add(it.toString()) }
        val length = if (item.isShow) showLength(item) else runtime(item.runtimeMs)
        length.takeIf { it.isNotEmpty() }?.let { add(it) }
        item.ageRating?.takeIf { it.isNotBlank() }?.let { add(it) }
        item.genres.take(maxGenres).takeIf { it.isNotEmpty() }?.let { add(it.joinToString(", ")) }
    }.joinToString(" · ")

    /**
     * `5 seasons`, or `8 episodes` for a single-season show (where "1 season" says nothing), or
     * empty when the catalogue gave no episodes at all.
     */
    fun showLength(item: MediaItem): String = when {
        item.seasonCount >= 2 -> "${item.seasonCount} seasons"
        item.episodeCount == 1 -> "1 episode"
        item.episodeCount > 1 -> "${item.episodeCount} episodes"
        else -> ""
    }

    /** `8.4` rating badge text, or null when the server gave no rating. */
    fun rating(value: Double?): String? =
        value?.takeIf { it > 0 }?.let { String.format(Locale.US, "%.1f", it) }

    /** Percentage complete, 0..100, for accessibility descriptions. */
    fun percentComplete(positionMs: Long, durationMs: Long): Int =
        if (durationMs <= 0) 0 else ((positionMs.toDouble() / durationMs) * 100).roundToInt().coerceIn(0, 100)

    /**
     * A section number in lower-case roman numerals with a full stop: `i.`, `iv.`, `xii.`. Past 39
     * — more sections than any page has — it falls back to digits.
     */
    fun sectionNumeral(n: Int): String {
        if (n !in 1..39) return "$n."
        return "x".repeat(n / 10) + ROMAN_UNITS[n % 10] + "."
    }

    private val ROMAN_UNITS = arrayOf("", "i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix")

    /** A row number set on a poster: `01`, `12`, `100`. */
    fun rank(n: Int): String = n.toString().padStart(2, '0')

    /** Download speed: `1.2 MB/s`, `840 KB/s`, `0 KB/s`. */
    fun speed(bytesPerSecond: Int): String {
        val bps = bytesPerSecond.coerceAtLeast(0)
        return when {
            bps >= 1_000_000 -> String.format(Locale.US, "%.1f MB/s", bps / 1_000_000.0)
            else -> "${bps / 1000} KB/s"
        }
    }
}
