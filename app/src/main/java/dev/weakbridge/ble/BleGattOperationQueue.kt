package dev.weakbridge.ble

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class GattFailure(val status: Int) : Exception("GATT status $status")

/** FIFO coroutine mutex; exact callback keys; a timed-out link is poisoned until replaced. */
class BleGattOperationQueue(private val timeoutMs: Long = 12_000, private val onFatal: () -> Unit) {
    private val mutex = Mutex()
    private var pending: Pair<String, CompletableDeferred<ByteArray>>? = null
    private var closed = false

    suspend fun run(key: String, start: () -> Boolean): ByteArray =
        checkNotNull(runIfCurrent(key, { true }, start))

    suspend fun runIfCurrent(key: String, valid: () -> Boolean, start: () -> Boolean): ByteArray? =
        mutex.withLock {
            check(!closed) { "GATT queue closed" }
            if (!valid()) return@withLock null
            val reply = CompletableDeferred<ByteArray>()
            pending = key to reply
            try {
                check(start()) { "GATT operation rejected" }
                withTimeout(timeoutMs) { reply.await() }
            } catch (e: CancellationException) {
                close()
                onFatal()
                throw e
            } finally {
                pending = null
            }
        }

    fun complete(key: String, status: Int, bytes: ByteArray = byteArrayOf()) {
        pending
            ?.takeIf { it.first == key }
            ?.second
            ?.let {
                if (status == 0) it.complete(bytes.copyOf())
                else it.completeExceptionally(GattFailure(status))
            }
    }

    fun close() {
        closed = true
        pending?.second?.cancel()
    }
}
