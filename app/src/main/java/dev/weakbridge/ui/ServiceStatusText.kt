package dev.weakbridge.ui

import dev.weakbridge.ble.BridgeStatus
import dev.weakbridge.ble.ConnectionState

fun serviceStatusText(
    status: BridgeStatus,
    subscribed: Boolean,
    enabled: Boolean = true,
    permission: Boolean = true,
): String =
    when {
        !enabled -> "Выключено в настройках"
        !permission -> "Нужно разрешение Android"
        subscribed -> "Подключено"
        status.state == ConnectionState.CONNECTED -> "iPhone не предоставил сервис"
        status.state in
            setOf(
                ConnectionState.DISCOVERING_SERVICES,
                ConnectionState.AUTHORIZING,
                ConnectionState.SUBSCRIBING,
            ) -> "Настраивается"
        else -> "Ожидает подключения iPhone"
    }
