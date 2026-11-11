package dev.weakbridge.ams

import android.os.SystemClock
import dev.weakbridge.ble.GattFailure
import dev.weakbridge.ble.GattTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AmsClient(
    private val scope: CoroutineScope,
    private val transport: GattTransport,
    private val fatal: () -> Unit,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    val state = MutableStateFlow(MediaState())
    private val reads = linkedMapOf<Pair<Int, Int>, Pair<EntityUpdate, Long>>()
    private val readSignal = Channel<Unit>(Channel.CONFLATED)
    private val versions = mutableMapOf<Pair<Int, Int>, Long>()
    private val commands = Channel<Pair<Int, Long>>(8)
    private val jobs = mutableListOf<Job>()
    private var recovery: Job? = null
    private var recoveries = 0
    private var registered = false
    private val subscriptions = Mutex()

    suspend fun start() {
        state.value = MediaState(available = true)
        jobs +=
            scope.launch {
                for (ignored in readSignal) {
                    while (reads.isNotEmpty()) {
                        val (u, version) = reads.remove(reads.keys.first()) ?: continue
                        try {
                            val key = u.entity to u.attribute
                            if (
                                versions[key] != version ||
                                    !transport.has(Ams.SERVICE, Ams.ATTRIBUTE)
                            )
                                continue
                            val selected =
                                transport.writeIfCurrent(
                                    Ams.SERVICE,
                                    Ams.ATTRIBUTE,
                                    byteArrayOf(u.entity.toByte(), u.attribute.toByte()),
                                ) {
                                    versions[key] == version
                                }
                            if (!selected || versions[key] != version) continue
                            val value = transport.read(Ams.SERVICE, Ams.ATTRIBUTE)
                            if (versions[key] == version && value.size <= 4096)
                                apply(
                                    u.copy(
                                        truncated = false,
                                        value = value.toString(Charsets.UTF_8),
                                    )
                                )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: GattFailure) {
                            if (e.status !in 0xA0..0xA2) fatal()
                        } catch (e: Exception) {
                            fatal()
                        }
                    }
                }
            }
        jobs +=
            scope.launch {
                for ((command, at) in commands) {
                    if (now() - at > 1500 || command !in state.value.supported) continue
                    try {
                        transport.writeIfCurrent(
                            Ams.SERVICE,
                            Ams.COMMAND,
                            byteArrayOf(command.toByte()),
                        ) {
                            state.value.available &&
                                now() - at <= 1500 &&
                                command in state.value.supported
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: GattFailure) {
                        if (e.status !in 0xA0..0xA2) fatal()
                    } catch (e: Exception) {
                        fatal()
                    }
                }
            }
        transport.subscribe(Ams.SERVICE, Ams.COMMAND)
        transport.subscribe(Ams.SERVICE, Ams.UPDATE)
        register()
        registered = true
        recoverIfNeeded()
    }

    private suspend fun register() {
        transport.write(Ams.SERVICE, Ams.UPDATE, byteArrayOf(0, 0, 1, 2))
        transport.write(Ams.SERVICE, Ams.UPDATE, byteArrayOf(1, 0))
        transport.write(Ams.SERVICE, Ams.UPDATE, byteArrayOf(2, 0, 1, 2, 3))
    }

    fun supported(bytes: ByteArray) {
        state.value = state.value.copy(supported = Ams.commands(bytes))
    }

    fun update(bytes: ByteArray) {
        val u = AmsParser.update(bytes) ?: return
        val key = u.entity to u.attribute
        val version = (versions[key] ?: 0) + 1
        versions[key] = version
        if (u.truncated) {
            // There are only eleven valid entity/attribute keys. Keep their newest requests
            // instead of letting stale revisions occupy a bounded FIFO and drop fresh data.
            reads[key] = u to version
            readSignal.trySend(Unit)
        } else {
            reads.remove(key)
            apply(u)
        }
    }

    private fun apply(u: EntityUpdate) {
        state.value = AmsParser.reduce(state.value, u, now())
        recoverIfNeeded()
    }

    private fun recoverIfNeeded() {
        val s = state.value
        if (
            !registered ||
                s.playback?.state != 1 ||
                s.title.isNotEmpty() ||
                s.artist.isNotEmpty() ||
                recoveries >= 2 ||
                recovery?.isActive == true
        )
            return
        recovery =
            scope.launch {
                delay(5_000)
                val current = state.value
                if (
                    current.available &&
                        current.playback?.state == 1 &&
                        current.title.isEmpty() &&
                        current.artist.isEmpty()
                ) {
                    recoveries++
                    try {
                        subscriptions.withLock {
                            transport.subscribe(Ams.SERVICE, Ams.UPDATE, false)
                            transport.subscribe(Ams.SERVICE, Ams.UPDATE)
                            register()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        fatal()
                    }
                }
                recovery = null
                if (recoveries == 1) recoverIfNeeded()
            }
    }

    fun command(id: Int) {
        if (state.value.available && id in state.value.supported) commands.trySend(id to now())
    }

    fun close() {
        jobs.forEach { it.cancel() }
        recovery?.cancel()
        readSignal.close()
        reads.clear()
        commands.close()
        state.value = MediaState()
    }
}
