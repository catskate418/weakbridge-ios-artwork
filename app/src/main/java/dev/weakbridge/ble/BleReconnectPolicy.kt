package dev.weakbridge.ble

import kotlin.random.Random

enum class ConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    BONDING,
    DISCOVERING_SERVICES,
    AUTHORIZING,
    SUBSCRIBING,
    CONNECTED,
    RECONNECTING,
    ERROR,
}

data class BridgeStatus(
    val state: ConnectionState = ConnectionState.DISCONNECTED,
    val detail: String = "Для начала нажмите «Подключить iPhone»",
    val notifications: Boolean = false,
    val media: Boolean = false,
)

class ConnectionGeneration {
    var value = 0L
        private set

    fun next(): Long = ++value

    fun accepts(generation: Long) = generation == value
}

class BleReconnectPolicy(private val jitter: () -> Double = { Random.nextDouble() }) {
    fun delayMs(attempt: Int): Long? {
        if (attempt !in 0..5) return null
        val base = minOf(300_000L, 10_000L shl attempt)
        return (base * (0.85 + jitter().coerceIn(0.0, 1.0) * 0.30)).toLong()
    }
}

/** Setup phases must advance in order; user actions and failures can interrupt any phase. */
class ConnectionStateMachine {
    var state = ConnectionState.DISCONNECTED
        private set

    fun transition(next: ConnectionState) {
        val interrupt =
            next in
                setOf(
                    ConnectionState.DISCONNECTED,
                    ConnectionState.ERROR,
                    ConnectionState.RECONNECTING,
                    ConnectionState.CONNECTING,
                    ConnectionState.BONDING,
                    ConnectionState.SCANNING,
                )
        val valid =
            interrupt ||
                next == state ||
                when (next) {
                    ConnectionState.DISCOVERING_SERVICES ->
                        state in setOf(ConnectionState.CONNECTING, ConnectionState.RECONNECTING)
                    ConnectionState.AUTHORIZING -> state == ConnectionState.DISCOVERING_SERVICES
                    ConnectionState.SUBSCRIBING -> state == ConnectionState.AUTHORIZING
                    ConnectionState.CONNECTED -> state == ConnectionState.SUBSCRIBING
                    else -> false
                }
        check(valid) { "Invalid BLE state transition: $state to $next" }
        state = next
    }
}
