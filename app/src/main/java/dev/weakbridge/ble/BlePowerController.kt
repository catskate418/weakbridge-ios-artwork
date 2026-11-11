package dev.weakbridge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt

/** Only changes at setup/idle boundaries. Requests are controller hints, not guarantees. */
@SuppressLint("MissingPermission")
class BlePowerController {
    private var last: Int? = null

    fun setup(gatt: BluetoothGatt) = request(gatt, BluetoothGatt.CONNECTION_PRIORITY_BALANCED)

    fun idle(gatt: BluetoothGatt, lowPower: Boolean) =
        request(
            gatt,
            if (lowPower) BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
            else BluetoothGatt.CONNECTION_PRIORITY_BALANCED,
        )

    private fun request(gatt: BluetoothGatt, priority: Int) {
        if (last != priority && gatt.requestConnectionPriority(priority)) last = priority
    }
}
