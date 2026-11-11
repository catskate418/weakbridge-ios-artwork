package dev.weakbridge.ancs

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

object Ancs {
    val SERVICE: UUID = UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0")
    val SOURCE: UUID = UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD")
    val CONTROL: UUID = UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9")
    val DATA: UUID = UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB")
    val attributes = listOf(0, 1, 2, 3, 6, 7)
    val categories =
        listOf(
            "Other",
            "Incoming calls",
            "Missed calls",
            "Voicemail",
            "Social",
            "Schedule",
            "Email",
            "News",
            "Health",
            "Business",
            "Location",
            "Entertainment",
        )

    fun request(uid: Long): ByteArray =
        ByteBuffer.allocate(17)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(0)
            .putInt(uid.toInt())
            .put(0)
            .put(1)
            .putShort(128)
            .put(2)
            .putShort(128)
            .put(3)
            .putShort(512)
            .put(6)
            .put(7)
            .array()

    fun action(uid: Long, positive: Boolean): ByteArray =
        ByteBuffer.allocate(6)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(2)
            .putInt(uid.toInt())
            .put(if (positive) 0.toByte() else 1.toByte())
            .array()
}

data class AncsEvent(
    val type: Int,
    val flags: Int,
    val category: Int,
    val count: Int,
    val uid: Long,
) {
    val preExisting
        get() = flags and 4 != 0

    fun supports(positive: Boolean) = flags and (if (positive) 8 else 16) != 0
}

object AncsParser {
    fun event(bytes: ByteArray): AncsEvent? {
        if (bytes.size != 8) return null
        val type = bytes[0].toInt() and 255
        val category = bytes[2].toInt() and 255
        if (type !in 0..2 || category !in 0..11) return null
        return AncsEvent(
            type,
            bytes[1].toInt() and 255,
            category,
            bytes[3].toInt() and 255,
            ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL,
        )
    }
}

/** One response per transaction; never interpret an unsolicited tail as a new header. */
class AncsAssembler(private val prefix: ByteArray, private val attributes: List<Int>) {
    private val buffer = ByteArrayOutputStream()
    private var complete = false

    fun feed(fragment: ByteArray): Map<Int, String>? {
        check(!complete) { "Response already complete" }
        require(buffer.size() + fragment.size <= 4096) { "ANCS response exceeds limit" }
        buffer.write(fragment)
        val bytes = buffer.toByteArray()
        for (i in 0 until minOf(prefix.size, bytes.size)) require(bytes[i] == prefix[i]) {
            "ANCS response identity mismatch"
        }
        if (bytes.size < prefix.size) return null
        var offset = prefix.size
        val result = linkedMapOf<Int, String>()
        for (id in attributes) {
            if (bytes.size - offset < 3) return null
            require((bytes[offset].toInt() and 255) == id) { "ANCS attribute order mismatch" }
            val length =
                (bytes[offset + 1].toInt() and 255) or ((bytes[offset + 2].toInt() and 255) shl 8)
            require(length <= 1024) { "ANCS attribute exceeds limit" }
            if (bytes.size - offset - 3 < length) return null
            result[id] = bytes.copyOfRange(offset + 3, offset + 3 + length).toString(Charsets.UTF_8)
            offset += 3 + length
        }
        require(offset == bytes.size) { "Trailing ANCS data" }
        complete = true
        return result
    }
}

/** Bounded session-local identities. Modified events always fetch (flags need not change). */
class AncsNotificationRepository(private val capacity: Int = 100) {
    data class Entry(val event: AncsEvent, val revision: Long)

    private val entries = linkedMapOf<Long, Entry>()
    private var revision = 0L

    fun accept(event: AncsEvent): Entry? {
        if (event.type == 2) {
            entries.remove(event.uid)
            return null
        }
        if (event.type == 0 && entries.containsKey(event.uid)) return null
        val entry = Entry(event, ++revision)
        entries[event.uid] = entry
        if (entries.size > capacity) entries.remove(entries.keys.first())
        return entry
    }

    fun current(uid: Long) = entries[uid]

    fun invalidate(uid: Long) {
        entries.remove(uid)
    }

    fun clear() = entries.clear()
}
