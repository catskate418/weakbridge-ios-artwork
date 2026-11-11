package dev.weakbridge.artwork

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.LruCache
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The two images AMS cannot carry: the current track's cover and each app's real icon.
 *
 * Everything is cached twice: in memory for instant reuse and on disk so a restart costs nothing.
 * Covers resolve from local audio first, then the public catalog. Lookups are serialised and
 * deduplicated, because a fast track skip would otherwise fire several requests at once and the
 * card would settle on whichever answer arrived last.
 */
class ArtworkService(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val local = LocalCoverLibrary(context)
    private val cache = CoverCache(context)
    private val queue = CoverQueue(context)
    private val remote = RemoteCatalog()
    private val misses = ConcurrentHashMap.newKeySet<String>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val gate = Mutex()
    private val coverMemory = LruCache<String, Bitmap>(12)
    private val iconMemory = LruCache<String, Bitmap>(64)
    private val pendingIcons = ConcurrentHashMap.newKeySet<String>()

    val hits = AtomicInteger()
    val missesCount = AtomicInteger()

    /** Delivered once per resolved cover; the media card repaints on this. */
    var onCover: ((String, Bitmap) -> Unit)? = null

    /** Delivered once per resolved app icon; cards with a real icon repaint on this. */
    var onIcon: ((String, Bitmap) -> Unit)? = null

    /**
     * Memory, then disk. Never touches the network, so callers can paint without delay.
     *
     * Album fallback matters for offline use: an album's cover is the same for every track on it,
     * so once one track from a release has been seen, the rest of that album paints instantly
     * without any request.
     */
    fun currentCover(title: String, artist: String, album: String): Bitmap? {
        val key = coverKey(title, artist, album)
        coverMemory.get(key)?.let {
            return it
        }
        val stored = cache.read(key) ?: albumCover(artist, album) ?: return null
        coverMemory.put(key, stored)
        return stored
    }

    private fun albumCover(artist: String, album: String): Bitmap? {
        if (album.isBlank()) return null
        val albumKey = coverKey("", artist, album).trim('|')
        if (albumKey.isBlank()) return null
        coverMemory.get(albumKey)?.let {
            return it
        }
        return cache.read(albumKey)?.also { coverMemory.put(albumKey, it) }
    }

    /**
     * Records the track and resolves its cover.
     *
     * A title is required: AMS delivers artist, album and title as separate events, so partial
     * updates would otherwise search for an empty title. The track is written to [CoverQueue]
     * before the attempt, because the watch is Wi-Fi only and often offline; queued tracks are
     * fetched later instead of being lost.
     */
    fun requestCover(title: String, artist: String, album: String) {
        if (title.isBlank()) return
        val key = coverKey(title, artist, album)
        if (currentCover(title, artist, album) != null) {
            queue.remove(title, artist, album)
            return
        }
        queue.add(title, artist, album)
        if (key in misses || !pending.add(key)) return
        scope.launch {
            try {
                val localHit = local.lookup(title, artist, album)
                if (localHit != null) {
                    store(title, artist, album, key, localHit)
                    return@launch
                }
                // Offline is not the same as "no such cover": leave it queued and do not remember
                // it as a miss, so Wi-Fi returning triggers another attempt.
                if (!online()) return@launch
                val cover = gate.withLock { remote.cover(title, artist, album) }
                if (cover == null) {
                    misses.add(key)
                    missesCount.incrementAndGet()
                    queue.remove(title, artist, album)
                    return@launch
                }
                store(title, artist, album, key, cover)
            } catch (_: Exception) {
                // Network failures stay queued; only a definitive answer clears the record.
            } finally {
                pending.remove(key)
            }
        }
    }

    private fun store(title: String, artist: String, album: String, key: String, cover: Bitmap) {
        cache.write(key, cover)
        coverMemory.put(key, cover)
        // The same art applies to every track on the release.
        val albumKey = coverKey("", artist, album).trim('|')
        if (albumKey.isNotBlank()) {
            cache.write(albumKey, cover)
            coverMemory.put(albumKey, cover)
        }
        hits.incrementAndGet()
        queue.remove(title, artist, album)
        onCover?.invoke(key, cover)
    }

    /**
     * Replays the queued tracks in one pass. Runs when the service starts and after a successful
     * lookup, both of which mean the watch is awake and likely online.
     */
    fun drainQueue() {
        if (!online()) return
        scope.launch {
            for (entry in queue.read()) {
                if (currentCover(entry.title, entry.artist, entry.album) != null) {
                    queue.remove(entry.title, entry.artist, entry.album)
                    continue
                }
                if (!online()) return@launch
                val key = coverKey(entry.title, entry.artist, entry.album)
                if (key in misses || !pending.add(key)) continue
                try {
                    val cover =
                        local.lookup(entry.title, entry.artist, entry.album)
                            ?: gate.withLock { remote.cover(entry.title, entry.artist, entry.album) }
                    if (cover == null) {
                        misses.add(key)
                        queue.remove(entry.title, entry.artist, entry.album)
                    } else {
                        store(entry.title, entry.artist, entry.album, key, cover)
                    }
                } catch (_: Exception) {
                    return@launch
                } finally {
                    pending.remove(key)
                }
            }
        }
    }

    /** True when the watch currently has a network that claims internet access. */
    fun online(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Number of tracks waiting for a network, shown in diagnostics. */
    fun queuedCount(): Int = queue.size()

    fun appIcon(bundleId: String): Bitmap? {
        iconMemory.get(bundleId)?.let {
            return it
        }
        val file = File(iconDir(), digest(bundleId) + ".png")
        if (!file.isFile) return null
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        iconMemory.put(bundleId, bitmap)
        return bitmap
    }

    fun requestIcon(bundleId: String, appName: String) {
        if (bundleId.isBlank() || appIcon(bundleId) != null || !pendingIcons.add(bundleId)) return
        scope.launch {
            try {
                val bitmap = remote.icon(bundleId, appName) ?: return@launch
                iconMemory.put(bundleId, bitmap)
                runCatching {
                    java.io.FileOutputStream(File(iconDir(), digest(bundleId) + ".png")).use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
                onIcon?.invoke(bundleId, bitmap)
            } finally {
                pendingIcons.remove(bundleId)
            }
        }
    }

    fun coverKey(title: String, artist: String, album: String): String =
        listOf(title, artist, album)
            .joinToString("|") { it.lowercase().trim().replace(Regex("\\s+"), " ") }
            .trim('|')

    fun iconDir() = File(context.filesDir, "icons").apply { mkdirs() }

    private fun digest(value: String) =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)
}
