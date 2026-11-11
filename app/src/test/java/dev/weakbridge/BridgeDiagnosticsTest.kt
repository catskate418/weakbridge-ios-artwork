package dev.weakbridge

import dev.weakbridge.diagnostics.BridgeDiagnostics
import org.junit.Assert.*
import org.junit.Test

class BridgeDiagnosticsTest {
    @Test
    fun resetInvalidatesAnOperationEvenAtTheSameClockInstant() {
        var clock = 0L
        val diagnostics = BridgeDiagnostics({ true }, { clock })
        val old = diagnostics.operationStarted("read")
        diagnostics.clear()
        val current = diagnostics.operationStarted("write")
        clock = 40
        diagnostics.operationFinished("read", old, success = false)
        diagnostics.operationFinished("write", current, success = true)

        val counts = diagnostics.measurements().counts
        assertEquals(1L, counts["gatt_operations"])
        assertEquals(40L, counts["gatt_active_ms"])
        assertFalse(counts.containsKey("gatt_read_failed"))
    }

    @Test
    fun disableThenEnableExcludesTheGapAndInvalidatesPendingOperations() {
        var clock = 0L
        var enabled = true
        val diagnostics = BridgeDiagnostics({ enabled }, { clock })
        diagnostics.connected()
        diagnostics.scan(true)
        val old = diagnostics.operationStarted("read")
        clock = 100
        enabled = false
        diagnostics.configurationChanged()
        clock = 1100
        enabled = true
        diagnostics.configurationChanged()
        clock = 1200
        diagnostics.operationFinished("read", old, success = true)

        val measured = diagnostics.measurements()
        assertEquals(200L, measured.connectedMs)
        assertEquals(200L, measured.scanMs)
        assertFalse(measured.counts.containsKey("gatt_active_ms"))
    }

    @Test
    fun enablingDuringAnExistingScanAndLinkStartsBothIntervals() {
        var clock = 0L
        var enabled = false
        val diagnostics = BridgeDiagnostics({ enabled }, { clock })
        diagnostics.scan(true)
        diagnostics.connected()
        clock = 500
        enabled = true
        diagnostics.configurationChanged()
        clock = 700
        assertEquals(200L, diagnostics.measurements().scanMs)
        assertEquals(200L, diagnostics.measurements().connectedMs)
        diagnostics.clear()
        clock = 1000
        assertEquals(300L, diagnostics.measurements().scanMs)
        assertEquals(300L, diagnostics.measurements().connectedMs)
        diagnostics.scan(false)
        diagnostics.disconnected()
        clock = 2000
        assertEquals(300L, diagnostics.measurements().scanMs)
        assertEquals(300L, diagnostics.measurements().connectedMs)
    }

    @Test
    fun duplicateLinkAndScanEventsDoNotResetMeasuredIntervals() {
        var clock = 0L
        val diagnostics = BridgeDiagnostics({ true }, { clock })
        diagnostics.connected()
        diagnostics.scan(true)
        clock = 100
        diagnostics.connected()
        diagnostics.scan(true)
        clock = 200

        val measured = diagnostics.measurements()
        assertEquals(200L, measured.connectedMs)
        assertEquals(200L, measured.scanMs)
        assertEquals(1L, measured.counts["scan_sessions"])
        assertEquals(1L, measured.counts["link_connected"])
    }
}
