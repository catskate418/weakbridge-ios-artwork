package dev.weakbridge.ble

/** Legacy advertisement: flags 3 + name header 2 + UTF-8 name + solicitation field 18. */
object PairingAdvertising {
    enum class Layout {
        COMBINED,
        SPLIT,
        NAME_TOO_LONG,
    }

    fun layout(name: String): Layout =
        when (name.toByteArray(Charsets.UTF_8).size) {
            in 1..8 -> Layout.COMBINED
            in 9..26 -> Layout.SPLIT
            else -> Layout.NAME_TOO_LONG
        }

    fun error(code: Int): String =
        when (code) {
            1 -> "Ошибка BLE-рекламы (1): имя или пакет слишком длинные"
            2 -> "Ошибка BLE-рекламы (2): нет свободного рекламного канала"
            3 -> "Ошибка BLE-рекламы (3): реклама уже запущена"
            4 -> "Ошибка BLE-рекламы (4): внутренняя ошибка Bluetooth"
            5 -> "Ошибка BLE-рекламы (5): функция не поддерживается"
            else -> "Ошибка BLE-рекламы: $code"
        }
}
