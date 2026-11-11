package dev.weakbridge.diagnostics

import android.content.Context
import android.os.BatteryManager
import android.os.SystemClock
import dev.weakbridge.BridgeSettings

/** Event-driven counters only. Never receives packet contents, peer names or addresses. */
class BridgeDiagnostics
internal constructor(private val enabled: () -> Boolean, private val now: () -> Long) {
    constructor(
        settings: BridgeSettings
    ) : this({ settings.diagnostics }, SystemClock::elapsedRealtime)

    data class OperationStamp internal constructor(val generation: Long, val startedAt: Long)

    internal data class Measurements(
        val connectedMs: Long,
        val scanMs: Long,
        val counts: Map<String, Long>,
    )

    private val counts = linkedMapOf<String, Long>()
    private val recent = ArrayDeque<String>()
    private var recording = enabled()
    private var generation = 0L
    private var linkConnected = false
    private var scanning = false
    private var connectedAt: Long? = null
    private var connectionMs = 0L
    private var scanAt: Long? = null
    private var scanMs = 0L

    private fun add(name: String, amount: Long = 1) {
        if (recording) counts[name] = (counts[name] ?: 0) + amount
    }

    fun event(name: String) {
        configurationChanged()
        if (!recording) return
        add(name)
        // High-frequency transport callbacks are counters, not individual journal entries.
        if (name !in setOf("callbacks", "gatt_operations", "ancs_fragments", "ams_events")) {
            if (recent.size == 32) recent.removeFirst()
            recent.addLast("${now() / 1000}s: $name")
        }
    }

    fun bytes(direction: String, count: Int) {
        configurationChanged()
        add("${direction}_bytes", count.toLong())
    }

    fun operationStarted(type: String): OperationStamp? {
        configurationChanged()
        if (!recording) return null
        add("gatt_operations")
        add("gatt_$type")
        return OperationStamp(generation, now())
    }

    fun operationFinished(type: String, stamp: OperationStamp?, success: Boolean) {
        configurationChanged()
        if (stamp == null || !recording || stamp.generation != generation) return
        add("gatt_active_ms", now() - stamp.startedAt)
        if (!success) event("gatt_${type}_failed")
    }

    fun configurationChanged() {
        val requested = enabled()
        if (requested == recording) return
        val instant = now()
        generation++
        recording = requested
        if (!recording) {
            connectedAt?.let { connectionMs += instant - it }
            connectedAt = null
            scanAt?.let { scanMs += instant - it }
            scanAt = null
        } else {
            if (linkConnected) connectedAt = instant
            if (scanning) scanAt = instant
        }
    }

    fun clear() {
        configurationChanged()
        generation++
        counts.clear()
        recent.clear()
        connectionMs = 0
        scanMs = 0
        val instant = now()
        connectedAt = instant.takeIf { recording && linkConnected }
        scanAt = instant.takeIf { recording && scanning }
    }

    fun connected() {
        configurationChanged()
        if (linkConnected) return
        linkConnected = true
        if (recording) connectedAt = now()
        event("link_connected")
    }

    fun disconnected() {
        configurationChanged()
        linkConnected = false
        connectedAt?.let { connectionMs += now() - it }
        connectedAt = null
    }

    fun scan(start: Boolean) {
        configurationChanged()
        if (start == scanning) return
        scanning = start
        if (start) {
            if (recording) scanAt = now()
            event("scan_sessions")
        } else if (!start) {
            scanAt?.let { scanMs += now() - it }
            scanAt = null
        }
    }

    internal fun measurements(): Measurements {
        configurationChanged()
        val instant = now()
        return Measurements(
            connectionMs + (connectedAt?.let { instant - it } ?: 0),
            scanMs + (scanAt?.let { instant - it } ?: 0),
            counts.toMap(),
        )
    }

    fun snapshot(context: Context): String {
        configurationChanged()
        if (!recording) return "Diagnostics disabled / Диагностика выключена"
        val measurements = measurements()
        val bm = context.getSystemService(BatteryManager::class.java)
        return buildString {
            appendLine("Connected ms: ${measurements.connectedMs}")
            appendLine("Scan ms: ${measurements.scanMs}")
            measurements.counts.forEach { (key, count) -> appendLine("$key: $count") }
            appendLine("Battery %: ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}")
            appendLine(
                "Charge µAh (MIN_VALUE = unavailable): ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)}"
            )
            appendLine(
                "Callbacks are an activity proxy, not CPU wakeups. GATT active ms excludes queue wait and operations spanning a diagnostics reset or toggle."
            )
            if (recent.isNotEmpty()) {
                appendLine("Recent events:")
                recent.forEach { appendLine(it) }
            }
        }
    }
}
