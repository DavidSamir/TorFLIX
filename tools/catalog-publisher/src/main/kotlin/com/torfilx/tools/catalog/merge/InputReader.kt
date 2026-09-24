package com.torfilx.tools.catalog.merge

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.util.zip.GZIPInputStream

/** A file that cannot be read at all, with the reason in words. */
internal class UnreadableFile(message: String) : Exception(message)

/**
 * Reads the JSON values held by one input file.
 *
 * Tolerant of how files are really saved: a byte order mark, UTF-16 (what Windows PowerShell 5.1
 * writes by default), gzip, comments and trailing commas, and JSON Lines. Every tolerance is said out
 * loud. Anything that would mean guessing, such as text that is not valid UTF-8, is refused with the
 * place it went wrong, because reading it anyway would publish garbled titles.
 */
internal object InputReader {

    /** A single input file larger than this, packed or unpacked, is refused rather than exhausting memory. */
    const val MAX_BYTES: Long = 256L * 1024 * 1024

    private val strictJson = Json
    private val tolerantJson = Json {
        allowComments = true
        allowTrailingComma = true
    }

    /**
     * The top-level values of [file]: the items of a list, a single title, or the titles in the lists
     * of a wrapping object. Null when the file cannot be read, which is logged at [failure].
     */
    fun values(file: InputFile, log: MergeLog, failure: Severity): List<JsonElement>? = try {
        val text = TextDecoding.decode(bytes(file)) { log.note(file.display, it) }
        if (text.isBlank()) throw UnreadableFile("is empty")
        if (file.jsonLines) lines(text, file, log, failure) else document(text, file, log)
    } catch (error: UnreadableFile) {
        log.add(failure, file.display, "${error.message}; the file was not used")
        null
    }

    private fun bytes(file: InputFile): ByteArray {
        if (file.size > MAX_BYTES) throw UnreadableFile("is ${file.size} bytes, over the $MAX_BYTES-byte limit for one file (split it)")
        val raw = try {
            Files.readAllBytes(file.path)
        } catch (error: IOException) {
            throw UnreadableFile("cannot be read: ${describe(error)}")
        }
        if (!file.gzip) return raw
        return try {
            gunzip(raw)
        } catch (error: IOException) {
            throw UnreadableFile("is not a valid .gz file: ${describe(error)}")
        }
    }

    private fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_BYTES) throw UnreadableFile("unpacks to more than $MAX_BYTES bytes")
            out.write(buffer, 0, read)
        }
        out.toByteArray()
    }

    /** One JSON document: strict JSON, then JSON with comments and trailing commas, then JSON Lines. */
    private fun document(text: String, file: InputFile, log: MergeLog): List<JsonElement> {
        val strictError = try {
            return shape(strictJson.parseToJsonElement(text), file, log)
        } catch (error: SerializationException) {
            error
        } catch (error: IllegalArgumentException) {
            error
        } catch (error: StackOverflowError) {
            throw UnreadableFile("is nested too deeply to read")
        }
        runCatching { tolerantJson.parseToJsonElement(text) }.getOrNull()?.let { element ->
            log.warning(file.display, "has comments or trailing commas, which JSON does not allow; read anyway")
            return shape(element, file, log)
        }
        val nonBlank = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (nonBlank.size >= 2) {
            val parsed = nonBlank.map { line -> runCatching { strictJson.parseToJsonElement(line) }.getOrNull() }
            if (parsed.all { it is JsonObject || it is JsonArray }) {
                log.note(file.display, "holds one JSON value per line (JSON Lines); read line by line")
                return parsed.filterNotNull().flatMap { if (it is JsonArray) it else listOf(it) }
            }
        }
        throw UnreadableFile("is not valid JSON: ${parseProblem(strictError, text)}")
    }

    /** JSON Lines: each line on its own, so one bad line costs only that line. */
    private fun lines(text: String, file: InputFile, log: MergeLog, failure: Severity): List<JsonElement> {
        val values = ArrayList<JsonElement>()
        text.lineSequence().forEachIndexed { index, line ->
            if (line.isBlank()) return@forEachIndexed
            try {
                val element = tolerantJson.parseToJsonElement(line)
                if (element is JsonArray) values.addAll(element) else values.add(element)
            } catch (error: SerializationException) {
                log.add(failure, "${file.display} line ${index + 1}", "is not valid JSON: ${parseProblem(error, line)}; the line was not used")
            } catch (error: IllegalArgumentException) {
                log.add(failure, "${file.display} line ${index + 1}", "is not valid JSON: ${parseProblem(error, line)}; the line was not used")
            }
        }
        return values
    }

    /**
     * What the top level of a file holds.
     *
     * A list is the catalogue format. A single object is one title when it has a title's fields, and
     * otherwise a wrapper whose lists of objects (`{"movies": [...], "shows": [...]}`) hold the titles.
     * A list nested inside the list is read too. Anything else is returned as it is, for the caller to
     * refuse item by item.
     */
    private fun shape(root: JsonElement, file: InputFile, log: MergeLog): List<JsonElement> = when (root) {
        is JsonArray -> {
            if (root.any { it is JsonArray }) log.warning(file.display, "holds lists inside the list; the titles in them were read")
            root.flatMap { if (it is JsonArray) it else listOf(it) }
        }
        is JsonObject -> when {
            root.keys.any { canonicalName(it) in ENTRY_MARKERS } -> {
                log.note(file.display, "holds a single title rather than a list of titles")
                listOf(root)
            }
            else -> {
                val lists = root.filterValues { value -> value is JsonArray && value.isNotEmpty() && value.all { it is JsonObject } }
                if (lists.isEmpty()) throw UnreadableFile("holds no titles: it is a JSON object with no title and no list of titles")
                log.note(file.display, "is an object; titles were read from its list${if (lists.size > 1) "s" else ""} ${lists.keys.joinToString { "\"$it\"" }}")
                lists.values.flatMap { it as JsonArray }
            }
        }
        else -> listOf(root)
    }

    /** The parser's complaint, with the line and column it points at. */
    private fun parseProblem(error: Exception, text: String): String {
        val message = error.message.orEmpty().substringBefore("\nJSON input:").lineSequence().first().trim()
        val offset = OFFSET.find(message)?.groupValues?.get(1)?.toIntOrNull()
        if (offset == null || offset > text.length) return message.ifEmpty { error::class.java.simpleName }
        var line = 1
        var column = 1
        for (index in 0 until offset) {
            if (text[index] == '\n') {
                line++
                column = 1
            } else {
                column++
            }
        }
        return "$message (line $line, column $column)"
    }

    private val OFFSET = Regex("""at offset (\d+)""")

    /** Top-level keys that make an object a title rather than a wrapper around lists of titles. */
    private val ENTRY_MARKERS = setOf("id", "title", "type", "magnets", "seasons")

    private const val BUFFER_BYTES = 64 * 1024
}

/** Turns file bytes into text, in whichever Unicode encoding they were saved. */
internal object TextDecoding {

    /**
     * The text of [bytes]. A byte order mark decides the encoding; without one, the zero bytes of
     * UTF-16 give it away (JSON begins with an ASCII character), and anything else must be valid UTF-8.
     *
     * @param notice told what was done when the file was not plain UTF-8.
     * @throws UnreadableFile when the bytes are not valid text in the encoding found.
     */
    fun decode(bytes: ByteArray, notice: (String) -> Unit = {}): String {
        fun at(index: Int) = if (index < bytes.size) bytes[index].toInt() and BYTE_MASK else -1
        val (charset, skip, description) = when {
            at(0) == 0xFF && at(1) == 0xFE && at(2) == 0 && at(3) == 0 -> Triple(Charset.forName("UTF-32LE"), 4, "UTF-32")
            at(0) == 0 && at(1) == 0 && at(2) == 0xFE && at(3) == 0xFF -> Triple(Charset.forName("UTF-32BE"), 4, "UTF-32")
            at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF -> Triple(Charsets.UTF_8, 3, null)
            at(0) == 0xFF && at(1) == 0xFE -> Triple(Charsets.UTF_16LE, 2, "UTF-16")
            at(0) == 0xFE && at(1) == 0xFF -> Triple(Charsets.UTF_16BE, 2, "UTF-16")
            at(0) > 0 && at(1) == 0 -> Triple(Charsets.UTF_16LE, 0, "UTF-16 without a byte order mark")
            at(0) == 0 && at(1) > 0 -> Triple(Charsets.UTF_16BE, 0, "UTF-16 without a byte order mark")
            else -> Triple(Charsets.UTF_8, 0, null)
        }
        description?.let { notice("saved as $it; read as such (UTF-8 is the usual encoding)") }
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = ByteBuffer.wrap(bytes, skip, bytes.size - skip)
        val output = CharBuffer.allocate(((bytes.size - skip) * decoder.maxCharsPerByte().toDouble()).toInt() + 1)
        val result = decoder.decode(input, output, true)
        if (result.isError) {
            throw UnreadableFile(
                "is not valid ${charset.name()} text (the first bad byte is at offset ${input.position()}); " +
                    "save it as UTF-8",
            )
        }
        decoder.flush(output)
        output.flip()
        return output.toString().removePrefix("﻿")
    }

    private const val BYTE_MASK = 0xFF
}
