package dev.weakbridge.ams

import java.util.UUID

object Ams {
    val SERVICE: UUID = UUID.fromString("89D3502B-0F36-433A-8EF4-C502AD55F8DC")
    val COMMAND: UUID = UUID.fromString("9B3C81D8-57B1-4A8A-B8DF-0E56F7CA51C2")
    val UPDATE: UUID = UUID.fromString("2F7CABCE-808D-411F-9A0C-BB92BA96C102")
    val ATTRIBUTE: UUID = UUID.fromString("C6B2F38C-23AB-46D8-A6AB-A3A870BBD5D7")
    const val PLAY = 0
    const val PAUSE = 1
    const val TOGGLE = 2
    const val NEXT = 3
    const val PREVIOUS = 4
    const val VOLUME_UP = 5
    const val VOLUME_DOWN = 6

    fun commands(bytes: ByteArray) = bytes.map { it.toInt() and 255 }.filter { it in 0..13 }.toSet()
}

data class EntityUpdate(
    val entity: Int,
    val attribute: Int,
    val truncated: Boolean,
    val value: String,
)

data class Playback(
    val state: Int,
    val rate: Float,
    val positionMs: Long,
    val measuredAt: Long,
    val positionKnown: Boolean = true,
)

data class MediaState(
    val available: Boolean = false,
    val player: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long? = null,
    val queueIndex: Long? = null,
    val playback: Playback? = null,
    val volume: Float? = null,
    val supported: Set<Int> = emptySet(),
    /** iOS marks explicit tracks in the title; kept as a flag so the card can draw a badge. */
    val explicit: Boolean = false,
)

/**
 * iOS marks explicit tracks with a combining enclosing circle (U+20DD) after a "!". The watch font
 * has no glyph for it, so it renders as an empty box that looks like a broken icon. The mark is
 * removed here and reported separately by [isExplicit] so the card can draw a proper badge instead
 * of spelling it out in the title. Other undrawable characters are dropped too.
 */
internal fun displayable(value: String): String {
    val out = StringBuilder(value.length)
    for (char in value) {
        when {
            char.category == CharCategory.UNASSIGNED -> Unit
            char.code in 0x20DD..0x20E4 -> Unit
            char.code in 0xFE00..0xFE0F -> Unit
            char.code in 0xE0000..0xE007F -> Unit
            char.isISOControl() -> Unit
            else -> out.append(char)
        }
    }
    return out.toString()
        // The enclosing circle is gone by now; the bang that introduced it must not linger either.
        .replace(Regex("\\s*!\\s*$"), "")
        .replace(Regex("\\s{2,}"), " ")
        .trim()
}

/** True when the title carries iOS's explicit-content mark, with or without the leading bang. */
internal fun isExplicit(value: String): Boolean =
    value.any { it.code in 0x20DD..0x20E4 }

object AmsParser {
    fun update(bytes: ByteArray): EntityUpdate? {
        if (bytes.size !in 3..4096) return null
        val entity = bytes[0].toInt() and 255
        val attribute = bytes[1].toInt() and 255
        if (entity !in 0..2 || attribute !in 0..(if (entity == 0) 2 else 3)) return null
        return EntityUpdate(
            entity,
            attribute,
            bytes[2].toInt() and 1 != 0,
            bytes.copyOfRange(3, bytes.size).toString(Charsets.UTF_8),
        )
    }

    fun playback(value: String, now: Long): Playback? {
        val parts = value.split(',')
        if (parts.size != 3) return null
        val state = parts[0].toIntOrNull() ?: return null
        val rate = parts[1].toFloatOrNull() ?: return null
        val position = parts[2].toDoubleOrNull() ?: return null
        if (
            state !in 0..3 ||
                !rate.isFinite() ||
                !position.isFinite() ||
                position < 0 ||
                position > 1e9
        )
            return null
        return Playback(state, if (state == 0) 0f else rate, (position * 1000).toLong(), now)
    }

    fun reduce(s: MediaState, u: EntityUpdate, now: Long): MediaState =
        when (u.entity to u.attribute) {
            0 to 0 ->
                if (u.value != s.player && s.player.isNotEmpty())
                    MediaState(
                        available = s.available,
                        player = displayable(u.value),
                        supported = s.supported,
                    )
                else s.copy(player = displayable(u.value))
            0 to 1 -> playback(u.value, now)?.let { s.copy(playback = it) } ?: s
            0 to 2 ->
                u.value
                    .toFloatOrNull()
                    ?.takeIf { it.isFinite() && it in 0f..1f }
                    ?.let { s.copy(volume = it) } ?: s
            1 to 0 ->
                u.value
                    .toLongOrNull()
                    ?.takeIf { it >= 0 }
                    ?.let { index ->
                        // AMS attributes arrive separately; new metadata may precede the queue
                        // update.
                        // Keep playback status; only position is uncertain until fresh
                        // PlaybackInfo.
                        if (s.queueIndex != null && s.queueIndex != index) {
                            s.copy(
                                queueIndex = index,
                                playback = s.playback?.copy(positionKnown = false),
                            )
                        } else s.copy(queueIndex = index)
                    } ?: s
            2 to 0 -> s.copy(artist = displayable(u.value))
            2 to 1 -> s.copy(album = displayable(u.value))
            2 to 2 ->
                s.copy(
                    title = displayable(u.value),
                    explicit = isExplicit(u.value),
                )
            2 to 3 ->
                s.copy(
                    durationMs =
                        u.value
                            .toDoubleOrNull()
                            ?.takeIf { it.isFinite() && it in 0.0..1e9 }
                            ?.let { (it * 1000).toLong() }
                )
            else -> s
        }
}
