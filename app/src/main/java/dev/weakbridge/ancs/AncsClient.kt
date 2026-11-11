package dev.weakbridge.ancs

import dev.weakbridge.ble.GattFailure
import dev.weakbridge.ble.GattTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

class AncsClient(
    private val scope: CoroutineScope,
    private val transport: GattTransport,
    private val allowed: (Int) -> Boolean,
    private val publish: (AncsEvent, Map<Int, String>, String?) -> Unit,
    private val remove: (Long) -> Unit,
    private val fatal: () -> Unit,
    private val appAllowed: (String) -> Boolean = { true },
    private val observeApp: (String, String?) -> Unit = { _, _ -> },
) {
    private val repository = AncsNotificationRepository()
    private val pending = linkedMapOf<Long, AncsNotificationRepository.Entry>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val appNames = linkedMapOf<String, String>()
    private val knownApps = linkedMapOf<Long, String>()
    private var assembler: AncsAssembler? = null
    private var response: CompletableDeferred<Map<Int, String>>? = null
    private var worker: Job? = null

    suspend fun start() {
        worker =
            scope.launch {
                try {
                    for (ignored in signal) {
                        while (pending.isNotEmpty()) {
                            val entry = pending.remove(pending.keys.first()) ?: continue
                            val e = entry.event
                            if (repository.current(e.uid) != entry) continue
                            val attrs =
                                try {
                                    request(
                                        Ancs.request(e.uid),
                                        Ancs.request(e.uid).copyOfRange(0, 5),
                                        Ancs.attributes,
                                    )
                                } catch (ex: GattFailure) {
                                    if (ex.status == 0xA2) continue else throw ex
                                }
                            // Do not spend another GATT transaction on an obsolete response.
                            if (repository.current(e.uid) != entry || !allowed(e.category)) continue
                            val appId = attrs[0].orEmpty()
                            knownApps[e.uid] = appId
                            if (knownApps.size > 100) knownApps.remove(knownApps.keys.first())
                            if (appId.isNotBlank()) observeApp(appId, appNames[appId])
                            if (!appAllowed(appId)) {
                                remove(e.uid)
                                continue
                            }
                            // Longer identifiers need a larger MTU or reliable writes; skip
                            // optional name lookup at default MTU.
                            if (
                                appId.isNotEmpty() &&
                                    appId.toByteArray().size <= 17 &&
                                    appId !in appNames
                            ) {
                                val prefix = byteArrayOf(1) + appId.toByteArray() + byteArrayOf(0)
                                val name =
                                    try {
                                        request(prefix + byteArrayOf(0), prefix, listOf(0))[0]
                                            .orEmpty()
                                    } catch (ex: GattFailure) {
                                        if (ex.status in 0xA0..0xA3) "" else throw ex
                                    }
                                if (appNames.size >= 64) appNames.remove(appNames.keys.first())
                                appNames[appId] = name
                            }
                            if (
                                repository.current(e.uid) == entry &&
                                    allowed(e.category) &&
                                    appAllowed(appId)
                            ) {
                                if (appId.isNotBlank()) observeApp(appId, appNames[appId])
                                publish(e, attrs, appNames[appId])
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fatal()
                }
            }
        transport.subscribe(Ancs.SERVICE, Ancs.DATA)
        transport.subscribe(Ancs.SERVICE, Ancs.SOURCE)
    }

    private suspend fun request(
        command: ByteArray,
        prefix: ByteArray,
        attributes: List<Int>,
    ): Map<Int, String> {
        val deferred = CompletableDeferred<Map<Int, String>>()
        response = deferred
        assembler = AncsAssembler(prefix, attributes)
        try {
            return withTimeout(10_000) {
                transport.write(Ancs.SERVICE, Ancs.CONTROL, command)
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            // ANCS has no response transaction ID beyond its header; never reuse a timed-out
            // stream.
            fatal()
            throw e
        } finally {
            response = null
            assembler = null
        }
    }

    fun source(bytes: ByteArray) {
        val e = AncsParser.event(bytes) ?: return
        if (e.type == 2) {
            repository.accept(e)
            knownApps.remove(e.uid)
            pending.remove(e.uid)
            remove(e.uid)
            return
        }
        if (!allowed(e.category) || knownApps[e.uid]?.let { !appAllowed(it) } == true) {
            repository.invalidate(e.uid)
            pending.remove(e.uid)
            remove(e.uid)
            return
        }
        if (e.preExisting && repository.current(e.uid) == null) return
        val entry = repository.accept(e) ?: return
        if (pending.size >= 64 && e.uid !in pending) pending.remove(pending.keys.first())
        pending[e.uid] = entry
        signal.trySend(Unit)
    }

    fun data(bytes: ByteArray) {
        try {
            assembler?.feed(bytes)?.let { response?.complete(it) }
        } catch (e: IllegalArgumentException) {
            response?.completeExceptionally(e)
        } catch (e: IllegalStateException) {
            response?.completeExceptionally(e)
        }
    }

    suspend fun action(uid: Long, positive: Boolean) {
        val e = repository.current(uid)?.event ?: return
        if (e.supports(positive) && allowed(e.category) && appAllowed(knownApps[uid].orEmpty()))
            transport.writeIfCurrent(Ancs.SERVICE, Ancs.CONTROL, Ancs.action(uid, positive)) {
                repository.current(uid)?.event == e &&
                    allowed(e.category) &&
                    appAllowed(knownApps[uid].orEmpty())
            }
    }

    fun close() {
        worker?.cancel()
        response?.cancel()
        signal.close()
        pending.clear()
        repository.clear()
        appNames.clear()
        knownApps.clear()
    }
}
