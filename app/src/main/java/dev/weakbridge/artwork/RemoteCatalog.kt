package dev.weakbridge.artwork

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Public catalog access for covers and app icons.
 *
 * Deezer comes first for covers: it indexes a much larger share of Russian releases than the
 * Apple store does, answers without a key, and returns a direct CDN image URL, so a hit needs two
 * short requests instead of a search plus a redirect. Apple stays as the second source for
 * tracks Deezer misses and for app icons.
 */
internal class RemoteCatalog {
    fun cover(title: String, artist: String, album: String): Bitmap? {
        if (title.isBlank()) return null
        return deezerCover(title, artist) ?: appleCover(title, artist, album)
    }

    /**
     * Deezer search matches on artist and title and returns the album cover, which is exactly
     * what the card needs. The response nests `artist` and `album` objects, so entries are parsed
     * with [JsonCursor] rather than by splitting strings.
     */
    private fun deezerCover(title: String, artist: String): Bitmap? {
        val query = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ")
        if (query.isBlank()) return null
        val json = text("https://api.deezer.com/search?limit=5&q=" + URLEncoder.encode(query, "UTF-8"))
            ?: return null
        val wantedTitle = clean(title)
        val wantedArtists = artists(artist)
        for (entry in JsonCursor(json).objects("data")) {
            val name = clean(entry.string("title") ?: continue)
            if (wantedTitle.isNotBlank() && !name.contains(wantedTitle.take(12))) continue
            val performer = clean(entry.child("artist")?.string("name") ?: "")
            if (wantedArtists.isNotEmpty() && wantedArtists.none { performer.contains(it) }) continue
            val cover = entry.child("album")?.string("cover_big") ?: continue
            if (!cover.startsWith("https://")) continue
            image(cover)?.let { return it }
        }
        return null
    }

    /**
     * Apple is queried with an explicit storefront. Without `country` the response follows the
     * calling IP, and for a Russian account that produced empty results for anything local.
     */
    private fun appleCover(title: String, artist: String, album: String): Bitmap? {
        val cleaned = clean(title)
        val queries =
            buildList {
                add("entity=song&term=" + URLEncoder.encode(listOf(artist, cleaned).joinToString(" "), "UTF-8"))
                add("entity=song&term=" + URLEncoder.encode(cleaned, "UTF-8"))
                if (album.isNotBlank())
                    add("entity=album&term=" + URLEncoder.encode("$artist ${clean(album)}", "UTF-8"))
            }
        for (query in queries) {
            for (country in listOf("ru", "us")) {
                val json = text("https://itunes.apple.com/search?limit=5&country=$country&$query") ?: continue
                pickAppleArtwork(json, title, artist, album)?.let { return image(it) }
            }
        }
        return null
    }

    fun icon(bundleId: String, appName: String): Bitmap? {
        // Exact bundle id first: a name search is a guess and must never outrank a known match.
        lookupIcon(bundleId)?.let { return it }
        if (appName.isBlank()) return null
        for (country in listOf("ru", "us")) {
            val json =
                text("https://itunes.apple.com/search?entity=software&limit=10&country=$country&term=" +
                    URLEncoder.encode(appName, "UTF-8"))
                    ?: continue
            // A search for "Swiftgram" also returns Messenger and other Telegram forks, because
            // they share the word "gram". Only an exact bundle id, or a name that starts with the
            // requested one, is accepted.
            for (entry in JsonCursor(json).objects("results")) {
                val id = entry.string("bundleId") ?: continue
                if (id == bundleId) return entry.artwork()?.let(::image)
            }
            val wanted = clean(appName)
            if (wanted.isBlank()) continue
            for (entry in JsonCursor(json).objects("results")) {
                val name = clean(entry.string("trackName") ?: continue)
                if (!name.startsWith(wanted)) continue
                return entry.artwork()?.let(::image)
            }
        }
        return null
    }

    private fun JsonCursor.artwork(): String? =
        string("artworkUrl100")?.replace("100x100", "300x300")

    /** Bundle ids resolve only inside a storefront that lists the app, so both are tried. */
    private fun lookupIcon(bundleId: String): Bitmap? {
        for (country in listOf("ru", "us")) {
            val json =
                text("https://itunes.apple.com/lookup?country=$country&bundleId=" +
                    URLEncoder.encode(bundleId, "UTF-8"))
                    ?: continue
            val url = field(json.substringAfter("\"results\":[", ""), "artworkUrl100")
                ?.replace("100x100", "300x300")
                ?: continue
            return image(url)
        }
        return null
    }

    private fun pickAppleArtwork(json: String, title: String, artist: String, album: String): String? {
        val wantedTitle = clean(title)
        val wantedArtists = artists(artist)
        val wantedAlbum = clean(album)
        if (json.contains("\"collectionName\"")) {
            for (chunk in json.split("\"collectionType\":\"Album\"").drop(1)) {
                val performer = clean(field(chunk, "artistName") ?: "")
                if (wantedArtists.isNotEmpty() && wantedArtists.none { performer.contains(it) }) continue
                val name = clean(field(chunk, "collectionName") ?: "")
                if (wantedAlbum.isNotBlank() && !name.contains(wantedAlbum.take(10))) continue
                return field(chunk, "artworkUrl100")?.replace("100x100", "600x600")
            }
            return null
        }
        for (chunk in json.split("\"wrapperType\":\"track\"").drop(1)) {
            val name = clean(field(chunk, "trackName") ?: continue)
            if (!name.contains(wantedTitle.take(12))) continue
            val performer = clean(field(chunk, "artistName") ?: "")
            if (wantedArtists.isNotEmpty() && wantedArtists.none { performer.contains(it) }) continue
            return field(chunk, "artworkUrl100")?.replace("100x100", "600x600")
        }
        return null
    }

    /**
     * AMS reports every credited artist in one string ("ICEGERGERT, OBLADAET") while catalogs list
     * them separately. Splitting lets a match on any single credited artist succeed, which is what
     * a listener means by "the same track".
     */
    private fun artists(value: String): List<String> =
        value.split(',', '&', '/', ';')
            .map(::clean)
            .filter { it.length >= 2 }

    /**
     * iOS titles routinely carry decorative characters ("Black Water !⃝"). Catalogs do not, so
     * matching happens on letters and digits only.
     */
    private fun clean(value: String) =
        value.filter { it.isLetterOrDigit() || it == ' ' }
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun field(json: String, name: String): String? {
        val key = "\"$name\":\""
        val start = json.indexOf(key)
        if (start < 0) return null
        val from = start + key.length
        val end = json.indexOf('"', from)
        return if (end > from) json.substring(from, end) else null
    }

    private fun text(url: String): String? =
        open(url)?.use {
            it.bufferedReader().readText()
        }

    private fun image(url: String): Bitmap? = open(url)?.use { BitmapFactory.decodeStream(it) }

    private fun open(url: String): java.io.InputStream? =
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "WeakBridge/1.0 (Wear OS)")
            connection.setRequestProperty("Accept", "*/*")
            val code = connection.responseCode
            if (code == 200) connection.inputStream else null
        } catch (_: Exception) {
            null
        }

    private companion object {
        const val TIMEOUT_MS = 3_000

    }
}
