package dev.weakbridge.artwork

import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import java.security.MessageDigest

/**
 * Artwork already on the watch.
 *
 * Yandex Music's offline cache is not usable here: its `.exo` files are encrypted ExoPlayer
 * container chunks, so there is no readable audio stream or embedded cover inside them. What does
 * work without any network is anything the user copied to the watch's own media library, plus any
 * plain audio file under a known music directory.
 */
class LocalCoverLibrary(private val context: Context) {
    private val musicRoots: List<java.io.File> =
        listOf(
                java.io.File("/sdcard/Music"),
                java.io.File("/sdcard/Download"),
                java.io.File("/sdcard/Ringtones"),
            ) + yandexDownloads()

    fun lookup(title: String, artist: String, album: String): Bitmap? {
        val wanted = normalize(title)
        if (wanted.isBlank()) return null
        val wantedArtist = normalize(artist)
        val candidates =
            musicRoots.asSequence()
                .filter { it.isDirectory }
                .flatMap { root ->
                    root.walkTopDown().maxDepth(5).filter { file ->
                        file.isFile && file.length() in MIN_BYTES..MAX_BYTES
                    }
                }
                .sortedByDescending {
                    val name = normalize(it.nameWithoutExtension)
                    (if (name.contains(wanted)) 2 else 0) +
                        (if (wantedArtist.isNotBlank() && name.contains(wantedArtist)) 1 else 0)
                }
                .take(MAX_SCAN)
                .toList()
        for (file in candidates) {
            val tags = tags(file) ?: continue
            if (normalize(tags.first) != wanted) continue
            if (wantedArtist.isNotBlank() && !normalize(tags.second).contains(wantedArtist)) continue
            cover(file)?.let { return it }
        }
        return mediaStore(title, artist, album)
    }

    private fun mediaStore(title: String, artist: String, album: String): Bitmap? {
        val selection = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (title.isNotBlank()) {
            selection += "${MediaStore.Audio.Media.TITLE} LIKE ?"
            args += "%$title%"
        }
        if (artist.isNotBlank()) {
            selection += "${MediaStore.Audio.Media.ARTIST} LIKE ?"
            args += "%$artist%"
        }
        if (selection.isEmpty()) return null
        val cursor: Cursor =
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID),
                selection.joinToString(" AND "),
                args.toTypedArray(),
                null,
            ) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            val uri =
                android.content.ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    it.getLong(0),
                )
            return cover(uri)
        }
    }

    private fun tags(file: java.io.File): Pair<String, String>? = withRetriever(file.absolutePath) { r ->
        val title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: return@withRetriever null
        title to (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "")
    }

    private fun cover(file: java.io.File): Bitmap? = withRetriever(file.absolutePath) { r ->
        r.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }

    private fun cover(uri: android.net.Uri): Bitmap? = try {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, uri)
        val picture = retriever.embeddedPicture
        retriever.release()
        picture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    } catch (_: Exception) {
        null
    }

    private inline fun <T> withRetriever(path: String, block: (MediaMetadataRetriever) -> T): T? =
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(path)
            val result = block(retriever)
            retriever.release()
            result
        } catch (_: Exception) {
            null
        }

    private fun normalize(value: String) =
        value.lowercase().trim().replace(Regex("\\s+"), " ")

    /** Yandex does keep plain audio for some downloads; those files do carry embedded art. */
    private fun yandexDownloads(): List<java.io.File> =
        listOf(
            java.io.File("/sdcard/Android/data/$YANDEX_MUSIC/files"),
            java.io.File("/sdcard/Download/$YANDEX_MUSIC"),
        )

    private companion object {
        const val YANDEX_MUSIC = "ru.yandex.music"
        const val MIN_BYTES = 64_000L
        const val MAX_BYTES = 64_000_000L
        const val MAX_SCAN = 40
    }
}

/** Persistent cover cache: a resolved track never pays for another lookup. */
class CoverCache(context: Context) {
    private val dir = java.io.File(context.filesDir, "covers").apply { mkdirs() }

    fun read(key: String): Bitmap? {
        val file = fileFor(key)
        return if (file.isFile) BitmapFactory.decodeFile(file.absolutePath) else null
    }

    /** Filename digest for a resolved cover; also used to recognise the cached entries. */
    fun nameFor(key: String) = digest(key) + ".png"

    fun write(key: String, bitmap: Bitmap) {
        val file = fileFor(key)
        if (file.isFile) return
        runCatching {
            java.io.FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun fileFor(key: String) = java.io.File(dir, digest(key) + ".png")

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)
}
