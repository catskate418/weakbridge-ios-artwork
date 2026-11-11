package dev.weakbridge

import dev.weakbridge.ble.*
import dev.weakbridge.ui.serviceStatusText
import org.junit.Assert.*
import org.junit.Test

class PairingUiTest {
    @Test
    fun advertisingBudgetUsesUtf8Bytes() {
        assertEquals(PairingAdvertising.Layout.COMBINED, PairingAdvertising.layout("Watch6"))
        assertEquals(PairingAdvertising.Layout.COMBINED, PairingAdvertising.layout("12345678"))
        assertEquals(PairingAdvertising.Layout.SPLIT, PairingAdvertising.layout("Galaxy Watch6"))
        assertEquals(PairingAdvertising.Layout.SPLIT, PairingAdvertising.layout("Часики"))
        assertEquals(PairingAdvertising.Layout.SPLIT, PairingAdvertising.layout("x".repeat(26)))
        assertEquals(
            PairingAdvertising.Layout.NAME_TOO_LONG,
            PairingAdvertising.layout("x".repeat(27)),
        )
        assertEquals(PairingAdvertising.Layout.NAME_TOO_LONG, PairingAdvertising.layout(""))
    }

    @Test
    fun permissionsAreNotServiceAvailability() {
        val disconnected = BridgeStatus()
        assertEquals("Ожидает подключения iPhone", serviceStatusText(disconnected, false))
        assertEquals(
            "Нужно разрешение Android",
            serviceStatusText(disconnected, false, permission = false),
        )
        assertEquals(
            "Выключено в настройках",
            serviceStatusText(disconnected, false, enabled = false),
        )
        assertEquals(
            "iPhone не предоставил сервис",
            serviceStatusText(BridgeStatus(ConnectionState.CONNECTED), false),
        )
        assertEquals("Подключено", serviceStatusText(BridgeStatus(ConnectionState.CONNECTED), true))
        assertEquals(
            "Настраивается",
            serviceStatusText(BridgeStatus(ConnectionState.SUBSCRIBING), false),
        )
    }

    @Test
    fun advertiseFailuresRetainErrorCode() {
        for (code in 1..5) assertTrue(PairingAdvertising.error(code).contains("($code)"))
        assertTrue(PairingAdvertising.error(99).contains("99"))
    }
}
