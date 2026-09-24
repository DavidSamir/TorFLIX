package com.torfilx.tools.catalog.merge

import java.io.IOException
import java.nio.file.FileSystemLoopException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumSet

/** One catalogue file found in an input folder. */
class InputFile(
    val path: Path,
    /** The folder's name and the path inside it, with `/`: how every message names the file. */
    val display: String,
    /** The path inside the folder, with `/`; what `--order name` sorts on. */
    val relative: String,
    val modifiedMs: Long,
    val size: Long,
    val gzip: Boolean,
    /** One JSON value per line (`.jsonl`, `.ndjson`) rather than one JSON document. */
    val jsonLines: Boolean,
)

/** How the files of the add folder are ranked from newest to oldest. */
enum class FileOrder(val description: String) {
    MODIFIED("newest first by modification time; files modified at the same moment by name"),
    NAME("newest first by name: the name that sorts last (2026-09-24 after 2026-09-01, batch-10 after batch-9) is the newest"),
    ;

    companion object {
        fun parse(text: String): FileOrder? = when (text.trim().lowercase()) {
            "mtime", "modified", "time", "date" -> MODIFIED
            "name" -> NAME
            else -> null
        }
    }
}

/** Finds catalogue files in a folder and ranks them. */
object InputFiles {

    private val FORMATS = listOf(
        ".json" to Format(gzip = false, lines = false),
        ".json.gz" to Format(gzip = true, lines = false),
        ".jsonl" to Format(gzip = false, lines = true),
        ".jsonl.gz" to Format(gzip = true, lines = true),
        ".ndjson" to Format(gzip = false, lines = true),
        ".ndjson.gz" to Format(gzip = true, lines = true),
    )

    private class Format(val gzip: Boolean, val lines: Boolean)

    /** The extensions read, for messages. */
    const val EXTENSIONS = ".json, .json.gz, .jsonl or .ndjson"

    /**
     * Every catalogue file under [root], in no particular order, including those in sub-folders.
     *
     * Hidden files and folders (a leading dot: `.git`, macOS `._name.json` resource forks) are passed
     * over, and so is anything that is not a catalogue file; a name that mentions `json` but does not
     * end in a catalogue extension (`films.json.txt`, as Windows makes when extensions are hidden) is a
     * warning, because it almost certainly was meant to be read. Links are followed, and a link that
     * loops back on itself is not followed twice.
     *
     * @param unreadable how much a file or folder that cannot be read matters: a warning for the add
     *   folder, an error for the remove folder, whose every instruction must be read.
     */
    fun discover(root: Path, label: String, log: MergeLog, unreadable: Severity): List<InputFile> {
        val found = ArrayList<InputFile>()
        fun relative(path: Path): String = root.relativize(path).joinToString("/")
        fun display(path: Path): String = if (path == root) label else "$label/${relative(path)}"

        Files.walkFileTree(
            root,
            EnumSet.of(FileVisitOption.FOLLOW_LINKS),
            Int.MAX_VALUE,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir != root && dir.fileName.toString().startsWith(".")) {
                        log.note(display(dir), "hidden folder, not read")
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val name = file.fileName.toString()
                    val where = display(file)
                    val format = formatOf(name)
                    when {
                        name.startsWith(".") -> log.note(where, "hidden file, not read")
                        attrs.isSymbolicLink -> log.add(unreadable, where, "is a link to something that does not exist")
                        !attrs.isRegularFile -> log.note(where, "not a regular file, not read")
                        format == null && "json" in name.lowercase() ->
                            log.warning(where, "not read: only names ending in $EXTENSIONS are catalogue files")
                        format == null -> log.tally(Severity.NOTE, "files that are not catalogue files were not read", where)
                        else -> found += InputFile(
                            path = file,
                            display = where,
                            relative = relative(file),
                            modifiedMs = attrs.lastModifiedTime().toMillis(),
                            size = attrs.size(),
                            gzip = format.gzip,
                            jsonLines = format.lines,
                        )
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    if (exc is FileSystemLoopException) {
                        log.note(display(file), "a folder link that loops back on itself, not followed")
                    } else {
                        log.add(unreadable, display(file), "cannot be read: ${describe(exc)}")
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) log.add(unreadable, display(dir), "could not be listed completely: ${describe(exc)}")
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return found
    }

    /** [files] from newest to oldest. The order is total, so the same folder always merges the same way. */
    fun newestFirst(files: List<InputFile>, order: FileOrder): List<InputFile> {
        val byName = Comparator<InputFile> { a, b -> NaturalOrder.compare(b.relative, a.relative) }
        return when (order) {
            FileOrder.MODIFIED -> files.sortedWith(compareByDescending<InputFile> { it.modifiedMs }.then(byName))
            FileOrder.NAME -> files.sortedWith(byName)
        }
    }

    private fun formatOf(name: String): Format? {
        val lower = name.lowercase()
        return FORMATS.filter { lower.endsWith(it.first) && lower.length > it.first.length }.maxByOrNull { it.first.length }?.second
    }
}

/**
 * Orders names the way a person reads them: runs of digits by their value, so `batch-9` comes before
 * `batch-10`, and letters without regard to case. Ties are broken by plain string order, so two
 * different names never compare equal.
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isAsciiDigit() && b[j].isAsciiDigit()) {
                val startA = i
                val startB = j
                while (i < a.length && a[i].isAsciiDigit()) i++
                while (j < b.length && b[j].isAsciiDigit()) j++
                val digitsA = a.substring(startA, i).trimStart('0')
                val digitsB = b.substring(startB, j).trimStart('0')
                if (digitsA.length != digitsB.length) return digitsA.length - digitsB.length
                val byValue = digitsA.compareTo(digitsB)
                if (byValue != 0) return byValue
            } else {
                val byLetter = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
                if (byLetter != 0) return byLetter
                i++
                j++
            }
        }
        val byLength = (a.length - i) - (b.length - j)
        return if (byLength != 0) byLength else a.compareTo(b)
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
}

/** An exception as a person would want to read it. */
internal fun describe(error: Throwable): String {
    val message = error.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
    return when {
        message.isEmpty() -> error::class.java.simpleName
        error is java.nio.file.AccessDeniedException -> "access denied"
        error is java.nio.file.NoSuchFileException -> "it no longer exists"
        else -> message
    }
}
