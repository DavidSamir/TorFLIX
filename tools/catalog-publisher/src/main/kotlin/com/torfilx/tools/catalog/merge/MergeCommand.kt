package com.torfilx.tools.catalog.merge

import com.torfilx.core.catalogue.crypto.Sha256
import com.torfilx.tools.catalog.Args
import com.torfilx.tools.catalog.CatalogPublisherCli
import com.torfilx.tools.catalog.UsageException
import java.io.File
import java.io.IOException
import java.io.PrintStream
import java.io.Writer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * `merge`: the add and remove folders in, one catalogue out, ready for `build`.
 *
 * Writes three files. The catalogue is written only when the merge succeeds, and a stale one from an
 * earlier run is deleted when it fails, so nothing old can be built by mistake. The report (every
 * finding, file by file) and the summary (key=value lines for scripts) are always written. Every file
 * is written beside its target and then moved into place, so a crash never leaves half a file.
 */
internal class MergeCommand(
    private val out: PrintStream,
    private val clock: () -> Long,
    private val resolve: (String) -> File,
) {

    fun run(args: Args): Int {
        val output = resolve(args.required("out")).absoluteFile
        val report = args.value("report")?.let(resolve)?.absoluteFile ?: File(output.parentFile, "merge-report.txt")
        val summary = args.value("summary")?.let(resolve)?.absoluteFile ?: File(output.parentFile, "merge-summary.properties")
        val options = MergeOptions(
            addDir = resolve(args.required("add")).absoluteFile,
            removeDir = args.value("remove")?.let(resolve)?.absoluteFile,
            order = args.value("order")?.let { FileOrder.parse(it) ?: throw UsageException("--order must be mtime or name, got \"$it\"") }
                ?: FileOrder.MODIFIED,
            mergeEpisodes = args.flag("merge-episodes"),
            stripTrackers = args.flag("strip-trackers"),
            releaseDirs = args.values("releases").map { resolve(it).absoluteFile },
            version = args.long("version")?.also { if (it <= 0) throw UsageException("--version must be 1 or more") },
            minVersionCode = args.int("min-version-code")?.also { if (it <= 0) throw UsageException("--min-version-code must be 1 or more") },
            appVersionCode = args.int("app-version-code")?.also { if (it <= 0) throw UsageException("--app-version-code must be 1 or more") },
            allowVanished = args.flag("allow-vanished"),
            maxVanishedPercent = args.double("max-vanished-percent")?.also {
                if (it.isNaN() || it < 0 || it > MAX_PERCENT) throw UsageException("--max-vanished-percent must be between 0 and 100")
            } ?: CatalogMerge.DEFAULT_MAX_VANISHED_PERCENT,
            outputs = listOf(output, report, summary),
        )
        val strict = args.flag("strict")

        out.println("Merging catalogue folders")
        out.println("  add       ${options.addDir.path}")
        out.println("  remove    ${options.removeDir?.path ?: "(none)"}")
        out.println("  order     ${options.order.description}")
        if (options.releaseDirs.isNotEmpty()) out.println("  releases  ${options.releaseDirs.joinToString { it.path }}")
        if (strict) out.println("  strict    any warning fails the run")

        val startedAt = clock()
        report.parentFile.mkdirs()
        val details = File.createTempFile("merge-details", ".part", report.parentFile)
        try {
            val (result, log) = details.bufferedWriter(Charsets.UTF_8).use { writer ->
                val log = MergeLog(writer, strict)
                CatalogMerge(options, log, progress = { out.println(it) }, clock).run() to log
            }
            val json = result.json
            if (json != null) {
                writeAtomically(output) { it.write(json) }
            } else if (output.isFile) {
                Files.delete(output.toPath())
            }
            writeAtomically(report) { stream ->
                stream.bufferedWriter(Charsets.UTF_8).let { writer ->
                    writeReportHeader(writer, options, strict, result, log, output, startedAt)
                    details.bufferedReader(Charsets.UTF_8).use { it.copyTo(writer) }
                    writer.flush()
                }
            }
            writeAtomically(summary) { stream ->
                stream.write(summaryText(result, log, output, json).toByteArray(Charsets.UTF_8))
            }
            printResult(result, log, output, report)
            return if (log.failed) CatalogPublisherCli.EXIT_FAILED else CatalogPublisherCli.EXIT_OK
        } catch (error: IOException) {
            throw IllegalStateException("could not write the merge's output: ${describe(error)}", error)
        } finally {
            details.delete()
        }
    }

    private fun printResult(result: MergeResult, log: MergeLog, output: File, report: File) {
        out.println()
        if (result.filesFound > 0) {
            out.println(
                "Read ${result.filesFound - result.filesUnreadable} of ${result.filesFound} files: ${result.copies} copies of titles, " +
                    "${result.superseded} older copies passed over, ${result.refused} refused",
            )
        }
        if (result.removeItems > 0) {
            out.println("Remove folder: ${result.removeItems} items removed ${result.removedTitles} titles and ${result.removedEpisodes} episodes")
        }
        if (result.entries.isNotEmpty()) {
            val shows = if (result.shows > 0) " (${result.films} films, ${result.shows} shows with ${result.episodes} episodes)" else ""
            out.println("Catalogue: ${result.entries.size} titles$shows, ${result.gzBytes} bytes compressed")
        }
        result.comparison?.let {
            out.println(
                "Compared with release ${it.version}: ${it.added} added, ${it.removed} removed, ${it.vanished} vanished, " +
                    "${it.changed} changed, ${it.unchanged} unchanged",
            )
        }
        result.version?.let { version ->
            val app = result.minVersionCode?.let { ", for app build $it or later" }.orEmpty()
            out.println(
                when {
                    log.failed -> "Would be release $version$app"
                    result.republish != null -> "Nothing changed: release $version is published again$app"
                    else -> "Release $version$app"
                },
            )
        }
        listOf(Severity.ERROR, Severity.WARNING).forEach { severity ->
            val count = log.count(severity)
            if (count == 0) return@forEach
            out.println()
            out.println("${severity.label.replaceFirstChar { it.uppercase() }}s ($count):")
            log.first(severity).forEach { out.println("  $it") }
            if (count > log.first(severity).size) out.println("  ... and ${count - log.first(severity).size} more in the report")
        }
        out.println()
        out.println("Report: ${report.path}")
        when {
            log.failed && log.count(Severity.ERROR) == 0 ->
                out.println("FAILED: --strict, and there are ${log.count(Severity.WARNING)} warnings. Nothing was written.")
            log.failed -> out.println("FAILED: ${log.count(Severity.ERROR)} errors. Nothing was written.")
            else -> out.println("OK: ${output.path}")
        }
    }

    private fun writeReportHeader(
        writer: Writer,
        options: MergeOptions,
        strict: Boolean,
        result: MergeResult,
        log: MergeLog,
        output: File,
        startedAt: Long,
    ) {
        fun line(label: String, value: Any?) = writer.append("  ${label.padEnd(LABEL_WIDTH)}$value\n")
        writer.append("TORFILX catalogue merge\n")
        line("run at", Instant.ofEpochMilli(startedAt))
        line(
            "result",
            when {
                !log.failed -> "OK, written to ${output.path}"
                log.count(Severity.ERROR) == 0 -> "FAILED under --strict: ${log.count(Severity.WARNING)} warnings; nothing written"
                else -> "FAILED: ${log.count(Severity.ERROR)} errors; nothing written"
            },
        )
        line("findings", "${log.count(Severity.ERROR)} errors, ${log.count(Severity.WARNING)} warnings, ${log.count(Severity.NOTE)} notes")
        line("add", options.addDir.path)
        line("remove", options.removeDir?.path ?: "(none)")
        line("order", options.order.description)
        line("strict", if (strict) "yes: any warning fails the run" else "no")
        line("episodes", if (options.mergeEpisodes) "merged across copies of a show" else "the newest copy of a show is used whole")
        line("files", "${result.filesFound} found, ${result.filesUnreadable} could not be read")
        line("copies", "${result.copies} read, ${result.superseded} older passed over, ${result.refused} refused")
        line("removed", "${result.removedTitles} titles and ${result.removedEpisodes} episodes, by ${result.removeItems} items")
        line("titles", "${result.entries.size}: ${result.films} films, ${result.shows} shows with ${result.episodes} episodes")
        result.comparison?.let {
            line(
                "vs release ${it.version}",
                "${it.added} added, ${it.removed} removed, ${it.vanished} vanished, ${it.changed} changed, ${it.unchanged} unchanged",
            )
        }
        line("version", result.version ?: "not settled")
        line("min build", result.minVersionCode ?: "any")
        writer.append("\nEverything the merge did, in order (errors and warnings are also counted above):\n\n")
    }

    /** `key=value` lines for the publishing scripts. Paths use `/`, so no escaping is ever needed. */
    private fun summaryText(result: MergeResult, log: MergeLog, output: File, json: ByteArray?): String = buildString {
        fun put(key: String, value: Any?) {
            if (value != null) append(key).append('=').append(value.toString().replace('\\', '/').replace("\n", " ")).append('\n')
        }
        put("status", if (log.failed) "failed" else "ok")
        put("errors", log.count(Severity.ERROR))
        put("warnings", log.count(Severity.WARNING))
        put("notes", log.count(Severity.NOTE))
        put("catalog", output.path.takeIf { json != null })
        put("sha256", json?.let { Sha256.hex(it) })
        put("json_bytes", json?.size)
        put("gz_bytes", result.gzBytes.takeIf { json != null })
        put("titles", result.entries.size)
        put("films", result.films)
        put("shows", result.shows)
        put("episodes", result.episodes)
        put("version", result.version)
        put("min_version_code", result.minVersionCode)
        put("previous_version", result.comparison?.version)
        put("republish", result.republish?.root?.path)
    }

    /** Writes [target] beside itself and moves it into place, so a failure never leaves half a file. */
    private fun writeAtomically(target: File, write: (java.io.OutputStream) -> Unit) {
        val directory = target.parentFile
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("cannot create ${directory.path}")
        val temporary = File.createTempFile(target.name, ".part", directory)
        try {
            temporary.outputStream().use(write)
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (notAtomic: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private companion object {
        const val LABEL_WIDTH = 14
        const val MAX_PERCENT = 100.0
    }
}
