package dev.weakbridge.artwork

import android.content.Context
import java.io.File

/**
 * Tracks whose cover could not be resolved while the watch had no network.
 *
 * The watch is Wi-Fi only, so it is offline most of the time it is on a wrist. Every track is
 * appended here as soon as it plays; a successful lookup removes it. When connectivity returns the
 * queue is drained in one pass, so covers for tracks heard earlier appear without replaying them.
 *
 * The file is plain text, one record per line, tab separated: title, artist, album. A malformed
 * line is skipped rather than failing the whole queue.
 */
class CoverQueue(context: Context) {
    private val file = File(context.filesDir, "cover_queue.tsv")

    fun add(title: String, artist: String, album: String) {
        if (title.isBlank()) return
        val record = listOf(title, artist, album).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }
        val existing = read()
        if (existing.any { it.title == title && it.artist == artist && it.album == album }) return
        runCatching {
            file.appendText(record + "\n")
            trim()
        }
    }

    fun remove(title: String, artist: String, album: String) {
        val keep = read().filterNot { it.title == title && it.artist == artist && it.album == album }
        runCatching {
            file.writeText(keep.joinToString("") { "${it.title}\t${it.artist}\t${it.album}\n" })
        }
    }

    fun read(): List<Entry> {
        if (!file.isFile) return emptyList()
        return runCatching {
            file.readLines()
                .mapNotNull { line ->
                    val parts = line.split('\t')
                    if (parts.size < 3 || parts[0].isBlank()) return@mapNotNull null
                    Entry(parts[0], parts[1], parts[2])
                }
        }.getOrDefault(emptyList())
    }

    fun size(): Int = read().size

    /** Keeps the file bounded: old requests are the least likely to still matter. */
    private fun trim() {
        val entries = read()
        if (entries.size <= MAX_ENTRIES) return
        file.writeText(
            entries.takeLast(MAX_ENTRIES)
                .joinToString("") { "${it.title}\t${it.artist}\t${it.album}\n" }
        )
    }

    data class Entry(val title: String, val artist: String, val album: String)

    private companion object {
        const val MAX_ENTRIES = 400
    }
}
