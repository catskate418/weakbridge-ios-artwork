package dev.weakbridge

import dev.weakbridge.ams.*
import dev.weakbridge.ancs.*
import dev.weakbridge.ble.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    private fun event(type: Int = 0, uid: Long = 0xffffffffL) = AncsEvent(type, 24, 6, 1, uid)

    private fun packet(uid: Long, vararg values: Pair<Int, String>): ByteArray {
        var bytes =
            ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).put(0).putInt(uid.toInt()).array()
        for ((id, value) in values) {
            val text = value.toByteArray()
            bytes += byteArrayOf(id.toByte(), text.size.toByte(), (text.size shr 8).toByte()) + text
        }
        return bytes
    }

    @Test
    fun unsignedNotificationId() {
        val e = AncsParser.event(byteArrayOf(0, 24, 6, 1, -1, -1, -1, -1))!!
        assertEquals(0xffffffffL, e.uid)
        assertTrue(e.supports(true))
        assertTrue(e.supports(false))
    }

    @Test
    fun malformedEvents() {
        for (size in 0..7) assertNull(AncsParser.event(ByteArray(size)))
        assertNull(AncsParser.event(byteArrayOf(3, 0, 0, 0, 0, 0, 0, 0)))
        assertNull(AncsParser.event(byteArrayOf(0, 0, 12, 0, 0, 0, 0, 0)))
    }

    @Test
    fun everyFragmentBoundaryIncludingUtf8AndLength() {
        val bytes = packet(42, 0 to "com.test", 1 to "Привет 🌙", 3 to "message")
        for (split in 1 until bytes.size) {
            val assembler = AncsAssembler(bytes.copyOfRange(0, 5), listOf(0, 1, 3))
            assertNull(assembler.feed(bytes.copyOfRange(0, split)))
            assertEquals("Привет 🌙", assembler.feed(bytes.copyOfRange(split, bytes.size))!![1])
        }
        val assembler = AncsAssembler(bytes.copyOfRange(0, 5), listOf(0, 1, 3))
        bytes.dropLast(1).forEach { assertNull(assembler.feed(byteArrayOf(it))) }
        assertEquals("message", assembler.feed(byteArrayOf(bytes.last()))!![3])
    }

    @Test
    fun emptyAttributes() {
        val bytes = packet(1, 1 to "", 3 to "")
        assertEquals(
            mapOf(1 to "", 3 to ""),
            AncsAssembler(bytes.copyOfRange(0, 5), listOf(1, 3)).feed(bytes),
        )
    }

    @Test
    fun wrongUidRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            AncsAssembler(packet(1).copyOfRange(0, 5), listOf(0)).feed(packet(2, 0 to "x"))
        }
    }

    @Test
    fun lengthAndOrderAreBounded() {
        val prefix = packet(1)
        assertThrows(IllegalArgumentException::class.java) {
            AncsAssembler(prefix, listOf(1)).feed(prefix + byteArrayOf(1, -1, -1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AncsAssembler(prefix, listOf(1)).feed(packet(1, 2 to "x"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AncsAssembler(prefix, listOf(1)).feed(ByteArray(4097))
        }
    }

    @Test
    fun appNameResponseFragmentation() {
        val prefix = byteArrayOf(1) + "app".toByteArray() + byteArrayOf(0)
        val a = AncsAssembler(prefix, listOf(0))
        assertNull(a.feed(prefix.copyOfRange(0, 2)))
        assertEquals(
            "Name",
            a.feed(
                prefix.copyOfRange(2, prefix.size) + byteArrayOf(0, 4, 0) + "Name".toByteArray()
            )!![0],
        )
    }

    @Test
    fun updateRemovalAndDuplicateIdentity() {
        val repo = AncsNotificationRepository()
        val original = repo.accept(event())!!
        assertNull(repo.accept(event()))
        val changed = repo.accept(event(1))!!
        assertNotEquals(original.revision, changed.revision)
        repo.accept(event(2))
        assertNull(repo.current(original.event.uid))
        assertNotNull(repo.accept(event()))
    }

    @Test
    fun notificationRepositoryBounded() {
        val repo = AncsNotificationRepository(2)
        (1L..3L).forEach { repo.accept(event(uid = it)) }
        assertNull(repo.current(1))
        assertNotNull(repo.current(3))
    }

    @Test
    fun commandsMatchSpec() {
        assertEquals(17, Ancs.request(1).size)
        assertArrayEquals(byteArrayOf(2, -1, -1, -1, -1, 1), Ancs.action(0xffffffffL, false))
        assertEquals(setOf(0, 1, 2, 6, 13), Ams.commands(byteArrayOf(0, 1, 2, 6, 13, 14, -1, 0)))
    }

    @Test
    fun amsEntityAndTruncation() {
        val u = AmsParser.update(byteArrayOf(2, 2, 1) + "Title".toByteArray())!!
        assertEquals(2, u.entity)
        assertEquals(2, u.attribute)
        assertTrue(u.truncated)
        assertNull(AmsParser.update(byteArrayOf(3, 0, 0)))
        assertNull(AmsParser.update(byteArrayOf(0, 3, 0)))
        assertNull(AmsParser.update(byteArrayOf(0, 1)))
        assertEquals("", AmsParser.update(byteArrayOf(2, 2, 0))!!.value)
    }

    @Test
    fun playbackMappingAndTiming() {
        assertEquals(Playback(1, 1f, 12500, 55), AmsParser.playback("1,1,12.5", 55))
        assertEquals(0f, AmsParser.playback("0,1,12.5", 55)!!.rate)
        assertEquals(-2f, AmsParser.playback("2,-2,12", 55)!!.rate)
        listOf("", "4,1,2", "1,NaN,2", "1,1,-1", "1,1,Infinity", "1,1").forEach {
            assertNull(AmsParser.playback(it, 0))
        }
    }

    @Test
    fun metadataEmptyAndPlayerChangeClearStaleTrack() {
        val s = MediaState(player = "A", title = "Old", playback = Playback(1, 1f, 50, 0))
        assertEquals("", AmsParser.reduce(s, EntityUpdate(2, 2, false, ""), 0).title)
        val changed = AmsParser.reduce(s, EntityUpdate(0, 0, false, "B"), 0)
        assertEquals("", changed.title)
        assertNull(changed.playback)
    }

    @Test
    fun boundedBackoff() {
        val low = BleReconnectPolicy { 0.0 }
        val high = BleReconnectPolicy { 1.0 }
        assertEquals(8500L, low.delayMs(0))
        assertEquals(11500L, high.delayMs(0))
        assertEquals(255000L, low.delayMs(5))
        assertNull(low.delayMs(6))
        assertNull(low.delayMs(-1))
        for (i in 1..5) assertTrue(low.delayMs(i)!! > low.delayMs(i - 1)!!)
    }

    @Test
    fun connectionStatesRequireSetupAndAllowRecovery() {
        val machine = ConnectionStateMachine()
        assertThrows(IllegalStateException::class.java) {
            machine.transition(ConnectionState.CONNECTED)
        }
        listOf(
                ConnectionState.CONNECTING,
                ConnectionState.DISCOVERING_SERVICES,
                ConnectionState.AUTHORIZING,
                ConnectionState.SUBSCRIBING,
                ConnectionState.CONNECTED,
                ConnectionState.CONNECTED,
                ConnectionState.RECONNECTING,
                ConnectionState.DISCOVERING_SERVICES,
                ConnectionState.ERROR,
                ConnectionState.DISCONNECTED,
            )
            .forEach(machine::transition)
        assertEquals(ConnectionState.DISCONNECTED, machine.state)
    }

    @Test
    fun queueTrackChangeInvalidatesOldPosition() {
        val s = MediaState(queueIndex = 1, title = "Old", playback = Playback(1, 1f, 50, 0))
        val changed = AmsParser.reduce(s, EntityUpdate(1, 0, false, "2"), 0)
        assertEquals(1, changed.playback!!.state)
        assertFalse(changed.playback.positionKnown)
        assertEquals("Old", changed.title)
        val refreshed = AmsParser.reduce(changed, EntityUpdate(0, 1, false, "1,1,0.5"), 100)
        assertTrue(refreshed.playback!!.positionKnown)
        assertEquals(500L, refreshed.playback.positionMs)
    }

    @Test
    fun queueUpdateAfterNewMetadataDoesNotEraseItOrPausePlayback() {
        var state = MediaState(available = true, queueIndex = 1, playback = Playback(0, 0f, 50, 0))
        state = AmsParser.reduce(state, EntityUpdate(2, 2, false, "New track"), 10)
        state = AmsParser.reduce(state, EntityUpdate(0, 1, false, "1,1,0"), 11)
        state = AmsParser.reduce(state, EntityUpdate(1, 0, false, "2"), 12)
        assertEquals("New track", state.title)
        assertEquals(1, state.playback!!.state)
        assertTrue(state.available)
    }

    @Test
    fun queueChangeKeepsPausedStateAndDoesNotInventPlayback() {
        val paused = MediaState(queueIndex = 1, playback = Playback(0, 0f, 50, 0))
        assertEquals(
            0,
            AmsParser.reduce(paused, EntityUpdate(1, 0, false, "2"), 10).playback!!.state,
        )
        assertNull(
            AmsParser.reduce(MediaState(queueIndex = 1), EntityUpdate(1, 0, false, "2"), 10)
                .playback
        )
        assertTrue(
            AmsParser.reduce(paused, EntityUpdate(1, 0, false, "1"), 10).playback!!.positionKnown
        )
    }

    @Test
    fun generationsRejectOldCallbacks() {
        val machine = ConnectionGeneration()
        val first = machine.next()
        assertTrue(machine.accepts(first))
        machine.next()
        assertFalse(machine.accepts(first))
    }
}
