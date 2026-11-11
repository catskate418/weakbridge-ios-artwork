package dev.weakbridge.integration

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.VolumeProviderCompat
import android.graphics.Bitmap
import dev.weakbridge.R
import dev.weakbridge.ams.*
import dev.weakbridge.artwork.ArtworkService
import dev.weakbridge.service.BridgeForegroundService

/** Remote session only. No local player, audio focus, audio track, or silent audio. */
class MediaSessionBridge(
    private val context: Context,
    private val command: (Int) -> Unit,
    private val artwork: ArtworkService,
    private val recordEvent: (String) -> Unit = {},
) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val session = MediaSessionCompat(context, "iPhone bridge")
    private var token = java.util.UUID.randomUUID().toString()
    private var supported: Set<Int> = emptySet()
    private var previous: MediaState? = null
    private val glyph = NotificationGlyph()
    private var cover: Bitmap? = null

    private fun playPause(id: Int) = command(if (id in supported) id else Ams.TOGGLE)

    /**
     * AMS only has "volume up" and "volume down", never an absolute level. A slider move is turned
     * into that many relative steps, and the reported level is clamped locally so the on-screen
     * slider does not jump while the phone catches up.
     */
    private val volume =
        object : VolumeProviderCompat(VOLUME_CONTROL_ABSOLUTE, STEPS, 50) {
            override fun onAdjustVolume(direction: Int) {
                if (direction == 0) return
                val step = if (direction > 0) STEPS_PER_DIAL_TICK else -STEPS_PER_DIAL_TICK
                applyLevel(currentVolume + step)
                repeat(STEPS_PER_DIAL_TICK) {
                    command(if (direction > 0) Ams.VOLUME_UP else Ams.VOLUME_DOWN)
                }
            }

            override fun onSetVolumeTo(volume: Int) {
                val target = volume.coerceIn(0, STEPS)
                val delta = target - currentVolume
                applyLevel(target)
                repeat(kotlin.math.abs(delta)) {
                    command(if (delta > 0) Ams.VOLUME_UP else Ams.VOLUME_DOWN)
                }
            }
        }

    private fun applyLevel(level: Int) {
        volume.setCurrentVolume(level.coerceIn(0, STEPS))
    }

    init {
        session.setPlaybackToRemote(volume)
        session.setCallback(
            object : MediaSessionCompat.Callback() {
                override fun onPlay() = playPause(Ams.PLAY)

                override fun onPause() = playPause(Ams.PAUSE)

                override fun onSkipToNext() = command(Ams.NEXT)

                override fun onSkipToPrevious() = command(Ams.PREVIOUS)
            }
        )
    }

    fun update(s: MediaState) {
        supported = s.supported
        val old = previous
        val active = s.available && s.playback != null
        if (session.isActive != active) session.isActive = active
        if (!active) {
            if (old != null) {
                token = java.util.UUID.randomUUID().toString()
                manager.cancel(2)
            }
            previous = null
            return
        }
        previous = s
        val fallbackLabel =
            NotificationAppCatalog.initials(s.artist.ifBlank { s.player.ifBlank { "iOS" } })
        val metadataChanged =
            old == null ||
                old.title != s.title ||
                old.artist != s.artist ||
                old.album != s.album ||
                old.durationMs != s.durationMs
        if (metadataChanged) {
            // Cached covers paint instantly; a miss starts a fetch that repaints via applyCover.
            cover = artwork.currentCover(s.title, s.artist, s.album)
            artwork.requestCover(s.title, s.artist, s.album)
            // Resolves on a worker; republish() brings the cover into the card and session.
            val metadata =
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, s.title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, s.artist)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, s.album)
            s.durationMs?.let { metadata.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, it) }
            cover?.let { base ->
                val marked = if (s.explicit) glyph.withExplicitBadge(base) else base
                metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, marked)
                metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, marked)
            }
            session.setMetadata(metadata.build())
            recordEvent("media_metadata_updates")
        }
        val actions =
            mapOf(
                Ams.PLAY to PlaybackStateCompat.ACTION_PLAY,
                Ams.PAUSE to PlaybackStateCompat.ACTION_PAUSE,
                Ams.TOGGLE to PlaybackStateCompat.ACTION_PLAY_PAUSE,
                Ams.NEXT to PlaybackStateCompat.ACTION_SKIP_TO_NEXT,
                Ams.PREVIOUS to PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS,
            )
        val p = s.playback
        if (old == null || old.playback != p || old.supported != s.supported) {
            recordEvent("media_state_updates")
            session.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(s.supported.fold(0L) { a, c -> a or (actions[c] ?: 0) })
                    .setState(
                        when (p?.state) {
                            0 -> PlaybackStateCompat.STATE_PAUSED
                            1 -> PlaybackStateCompat.STATE_PLAYING
                            2 -> PlaybackStateCompat.STATE_REWINDING
                            3 -> PlaybackStateCompat.STATE_FAST_FORWARDING
                            else -> PlaybackStateCompat.STATE_NONE
                        },
                        // AMS only resends elapsed time when it changes, so Android has to
                        // extrapolate from the reported position; reporting "unknown" would leave
                        // the watch progress bar frozen for the whole track.
                        p?.positionMs ?: PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                        p?.rate ?: 0f,
                        // measuredAt is elapsedRealtime; the state machine reads wall-clock time.
                        p?.measuredAt ?: 0,
                    )
                    .build()
            )
        }
        s.volume?.let { fraction ->
            val level = (fraction * STEPS).toInt()
            // Phone-side changes win over the optimistic local value.
            if (kotlin.math.abs(level - volume.currentVolume) > 1) applyLevel(level)
        }
        // Position and volume are extrapolated by the listening device, so they never republish
        // the card.
        if (
            old != null &&
                old.title == s.title &&
                old.artist == s.artist &&
                old.album == s.album &&
                old.player == s.player &&
                old.playback?.state == p?.state &&
                old.supported == s.supported
        )
            return
        publish(s)
    }

    /** Renders the media card from the current cover; called on track changes and cover arrival. */
    private fun publish(s: MediaState) {
        val p = s.playback
        val fallbackLabel =
            NotificationAppCatalog.initials(s.artist.ifBlank { s.player.ifBlank { "iOS" } })
        // The badge goes on whatever image the card shows, including the placeholder, so an
        // explicit track is never missing the mark.
        val fallback = glyph.largeArtwork(fallbackLabel, GENERIC_MEDIA)
        val artwork = (cover ?: fallback).let { base ->
            if (s.explicit) glyph.withExplicitBadge(base) else base
        }
        val notification =
            NotificationCompat.Builder(context, "media")
                .setSmallIcon(glyph.media(s.explicit))
                // A 1x1 placeholder until the cover lands; republish() swaps it in.
                .setLargeIcon(artwork)
                .setContentTitle(s.title.ifBlank { s.player.ifBlank { "iPhone media" } })
                .setContentText(s.artist)
                .setSubText(s.album.takeIf { it.isNotBlank() })
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setStyle(
                    androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(session.sessionToken)
                        .setShowActionsInCompactView(
                            *compactActions(s).take(3).map { it.first }.toIntArray()
                        )
                )
        val toggle =
            if (Ams.TOGGLE in s.supported) Ams.TOGGLE
            else if (p?.state == 1) Ams.PAUSE else Ams.PLAY
        listOf(
                Ams.PREVIOUS to "Previous",
                toggle to (if (p?.state == 1) "Pause" else "Play"),
                Ams.NEXT to "Next",
                Ams.VOLUME_DOWN to "Volume down",
                Ams.VOLUME_UP to "Volume up",
            )
            .forEach { (id, label) ->
                if (id in s.supported) {
                    val intent =
                        Intent(context, MediaCommandReceiver::class.java).apply {
                            data = Uri.parse("weakbridge://media/$token/$id")
                            putExtra("token", token)
                            putExtra("command", id)
                        }
                    notification.addAction(
                        R.drawable.ic_bridge,
                        label,
                        PendingIntent.getBroadcast(
                            context,
                            0,
                            intent,
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        ),
                    )
                }
            }
        manager.notify(2, notification.build())
        recordEvent("media_notification_posts")
    }

    /** Action indexes in the order they were added, limited to what the media style can show. */
    private fun compactActions(s: MediaState): List<Pair<Int, Int>> {
        val order = mutableListOf<Pair<Int, Int>>()
        val toggle = if (Ams.TOGGLE in s.supported) Ams.TOGGLE else Ams.PLAY
        if (Ams.PREVIOUS in s.supported) order += order.size to Ams.PREVIOUS
        if (toggle in s.supported) order += order.size to toggle
        if (Ams.NEXT in s.supported) order += order.size to Ams.NEXT
        return order.take(3)
    }

    /**
     * Repaints after a cover resolved asynchronously. The cover is re-read here because the
     * metadata path only runs when the track changes, and this call happens after that.
     */
    /**
     * Adopts a cover that resolved after the card was drawn. Republishing through [update] alone
     * is not enough: the metadata path only runs on a track change.
     */
    fun applyCover(bitmap: Bitmap) {
        cover = bitmap
        val state = previous ?: return
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, state.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, state.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, state.album)
                .apply {
                    state.durationMs?.let { putLong(MediaMetadataCompat.METADATA_KEY_DURATION, it) }
                }
                .putBitmap(
                    MediaMetadataCompat.METADATA_KEY_ALBUM_ART,
                    if (state.explicit) glyph.withExplicitBadge(bitmap) else bitmap,
                )
                .putBitmap(
                    MediaMetadataCompat.METADATA_KEY_ART,
                    if (state.explicit) glyph.withExplicitBadge(bitmap) else bitmap,
                )
                .build()
        )
        publish(state)
    }

    fun fromNotification(value: String?, id: Int) {
        if (value == token && session.isActive && id in supported) command(id)
    }

    fun close() {
        session.isActive = false
        session.release()
        manager.cancel(2)
    }

    private companion object {
        /** Artist, album, title and duration are all AMS offers; iOS sends no artwork. */
        const val GENERIC_MEDIA = 11

        /** AMS has no absolute volume, so levels are a local scale over relative commands. */
        const val STEPS = 16
        const val STEPS_PER_DIAL_TICK = 1
    }
}

class MediaCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        BridgeForegroundService.active?.mediaAction(
            intent.getStringExtra("token"),
            intent.getIntExtra("command", -1),
        )
    }
}
