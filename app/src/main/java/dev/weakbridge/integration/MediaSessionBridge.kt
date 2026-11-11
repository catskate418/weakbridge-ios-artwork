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
import dev.weakbridge.R
import dev.weakbridge.ams.*
import dev.weakbridge.service.BridgeForegroundService

/** Remote session only. No local player, audio focus, audio track, or silent audio. */
class MediaSessionBridge(
    private val context: Context,
    private val command: (Int) -> Unit,
    private val recordEvent: (String) -> Unit = {},
) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val session = MediaSessionCompat(context, "iPhone bridge")
    private var token = java.util.UUID.randomUUID().toString()
    private var supported: Set<Int> = emptySet()
    private var previous: MediaState? = null

    private fun playPause(id: Int) = command(if (id in supported) id else Ams.TOGGLE)

    private val volume =
        object : VolumeProviderCompat(VOLUME_CONTROL_RELATIVE, 100, 50) {
            override fun onAdjustVolume(direction: Int) {
                if (direction > 0) command(Ams.VOLUME_UP)
                else if (direction < 0) command(Ams.VOLUME_DOWN)
            }
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
        if (
            old == null ||
                old.title != s.title ||
                old.artist != s.artist ||
                old.album != s.album ||
                old.durationMs != s.durationMs
        ) {
            val metadata =
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, s.title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, s.artist)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, s.album)
            s.durationMs?.let { metadata.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, it) }
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
                        p?.takeIf { it.positionKnown }?.positionMs
                            ?: PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                        p?.rate ?: 0f,
                        p?.measuredAt ?: 0,
                    )
                    .build()
            )
        }
        s.volume?.let {
            if (volume.currentVolume != (it * 100).toInt())
                volume.setCurrentVolume((it * 100).toInt())
        }
        // Elapsed time and volume do not change the notification's presentation.
        if (
            old != null &&
                old.title == s.title &&
                old.artist == s.artist &&
                old.player == s.player &&
                old.playback?.state == p?.state &&
                old.supported == s.supported
        )
            return
        val notification =
            NotificationCompat.Builder(context, "media")
                .setSmallIcon(R.drawable.ic_bridge)
                .setContentTitle(s.title.ifBlank { s.player.ifBlank { "iPhone media" } })
                .setContentText(s.artist)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setStyle(
                    androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(session.sessionToken)
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

    fun fromNotification(value: String?, id: Int) {
        if (value == token && session.isActive && id in supported) command(id)
    }

    fun close() {
        session.isActive = false
        session.release()
        manager.cancel(2)
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
