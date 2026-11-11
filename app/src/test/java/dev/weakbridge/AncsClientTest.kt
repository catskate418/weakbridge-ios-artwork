package dev.weakbridge

import dev.weakbridge.ancs.*
import dev.weakbridge.ble.GattTransport
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AncsClientTest {
    private class Transport : GattTransport {
        val writes = mutableListOf<ByteArray>()

        override suspend fun subscribe(
            service: UUID,
            characteristic: UUID,
            enabled: Boolean,
            indication: Boolean,
        ) {}

        override suspend fun write(service: UUID, characteristic: UUID, value: ByteArray) {
            writes += value
        }

        override suspend fun read(service: UUID, characteristic: UUID) = byteArrayOf()

        override fun has(service: UUID, characteristic: UUID) = true
    }

    private fun event(type: Int, flags: Int = 0) =
        byteArrayOf(type.toByte(), flags.toByte(), 6, 1, 42, 0, 0, 0)

    private fun response(title: String = "title", appId: String = ""): ByteArray =
        byteArrayOf(0, 42, 0, 0, 0) +
            Ancs.attributes
                .flatMap { id ->
                    val bytes =
                        (when (id) {
                                0 -> appId
                                1 -> title
                                else -> ""
                            })
                            .toByteArray()
                    (byteArrayOf(id.toByte(), bytes.size.toByte(), 0) + bytes).toList()
                }
                .toByteArray()

    @Test
    fun removeDuringRequestDoesNotResurrectNotification() = runTest {
        val t = Transport()
        val published = mutableListOf<Long>()
        val removed = mutableListOf<Long>()
        val c =
            AncsClient(
                backgroundScope,
                t,
                { true },
                { e, _, _ -> published += e.uid },
                { removed += it },
                { error("fatal") },
            )
        c.start()
        c.source(event(0))
        runCurrent()
        assertEquals(1, t.writes.size)
        c.source(event(2))
        c.data(response())
        runCurrent()
        assertTrue(published.isEmpty())
        assertEquals(listOf(42L), removed)
        c.close()
    }

    @Test
    fun updatesSupersedeInFlightResponsesAndAdditionsDeduplicate() = runTest {
        val t = Transport()
        val titles = mutableListOf<String>()
        val c =
            AncsClient(
                backgroundScope,
                t,
                { true },
                { _, a, _ -> titles += a[1]!! },
                {},
                { error("fatal") },
            )
        c.start()
        c.source(event(0))
        c.source(event(0))
        runCurrent()
        assertEquals(1, t.writes.size)
        c.source(event(1))
        c.data(response("old"))
        runCurrent()
        assertTrue(titles.isEmpty())
        assertEquals(2, t.writes.size)
        c.data(response("new"))
        runCurrent()
        assertEquals(listOf("new"), titles)
        c.close()
    }

    @Test
    fun historicalAndFilteredEventsDoNotRequestContent() = runTest {
        val t = Transport()
        val c =
            AncsClient(
                backgroundScope,
                t,
                { false },
                { _, _, _ -> error("publish") },
                {},
                { error("fatal") },
            )
        c.start()
        c.source(event(0))
        runCurrent()
        assertTrue(t.writes.isEmpty())
        c.close()
        val d =
            AncsClient(
                backgroundScope,
                t,
                { true },
                { _, _, _ -> error("publish") },
                {},
                { error("fatal") },
            )
        d.start()
        d.source(event(0, 4))
        runCurrent()
        assertTrue(t.writes.isEmpty())
        d.close()
    }

    @Test
    fun responseTimeoutInvalidatesLink() = runTest {
        var fatal = 0
        val c = AncsClient(backgroundScope, Transport(), { true }, { _, _, _ -> }, {}, { fatal++ })
        c.start()
        c.source(event(0))
        runCurrent()
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(1, fatal)
        c.close()
    }

    @Test
    fun obsoleteResponseSkipsOptionalAppNameTransaction() = runTest {
        val transport = Transport()
        var published = false
        val client =
            AncsClient(
                backgroundScope,
                transport,
                { true },
                { _, _, _ -> published = true },
                {},
                { error("fatal") },
            )
        client.start()
        client.source(event(0))
        runCurrent()
        client.source(event(2))
        client.data(response(appId = "org.example.app"))
        runCurrent()
        assertEquals(1, transport.writes.size)
        assertFalse(published)
        client.close()
    }

    @Test
    fun blockedKnownAppSkipsSubsequentContentAndNameReads() = runTest {
        val transport = Transport()
        val seen = mutableListOf<String>()
        var allowed = false
        val client =
            AncsClient(
                backgroundScope,
                transport,
                { true },
                { _, _, _ -> error("Blocked publication") },
                {},
                { error("fatal") },
                appAllowed = { allowed },
                observeApp = { id, _ -> seen += id },
            )
        client.start()
        client.source(event(0))
        runCurrent()
        client.data(response(appId = "org.example.app"))
        runCurrent()
        assertEquals(listOf("org.example.app"), seen)
        assertEquals(1, transport.writes.size)
        repeat(20) { client.source(event(1)) }
        runCurrent()
        assertEquals(1, transport.writes.size)
        allowed = true
        client.source(event(1))
        runCurrent()
        assertEquals(2, transport.writes.size)
        client.close()
    }

    @Test
    fun categoryDisabledDuringFetchSkipsOptionalNameRead() = runTest {
        val transport = Transport()
        var categoryAllowed = true
        val client =
            AncsClient(
                backgroundScope,
                transport,
                { categoryAllowed },
                { _, _, _ -> error("Filtered publication") },
                {},
                { error("fatal") },
            )
        client.start()
        client.source(event(0))
        runCurrent()
        categoryAllowed = false
        client.data(response(appId = "org.example.app"))
        runCurrent()
        assertEquals(1, transport.writes.size)
        client.close()
    }
}
