package dev.weakbridge.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.weakbridge.diagnostics.BridgeDiagnostics
import java.util.UUID
import kotlinx.coroutines.*

/** All state/callbacks are confined to Main. One GATT and one queue per generation. */
@SuppressLint("MissingPermission")
class BleConnectionManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val diagnostics: BridgeDiagnostics,
    private val onLink: suspend (GattTransport) -> Unit,
    private val onValue: (UUID, ByteArray) -> Unit,
    private val onLost: (Int) -> Unit,
    private val onServiceChanged: () -> Unit,
) : GattTransport {
    private val generations = ConnectionGeneration()
    private var gatt: BluetoothGatt? = null
    private var queue: BleGattOperationQueue? = null
    private var timeout: Job? = null
    private var setup: Job? = null
    private var connected = false
    private var power = BlePowerController()

    private fun key(type: String, uuid: UUID? = null) = "$type:$uuid"

    fun connect(device: BluetoothDevice, passive: Boolean) {
        close()
        val generation = generations.next()
        val q = BleGattOperationQueue { if (generations.accepts(generation)) fail(-2) }
        queue = q
        val cb =
            object : BluetoothGattCallback() {
                fun active(g: BluetoothGatt) = generations.accepts(generation) && g === gatt

                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (!active(g)) {
                        g.close()
                        return
                    }
                    diagnostics.event("callbacks")
                    if (status != 0 || newState == BluetoothProfile.STATE_DISCONNECTED) {
                        fail(status)
                        return
                    }
                    if (newState == BluetoothProfile.STATE_CONNECTED && !connected) {
                        connected = true
                        timeout?.cancel()
                        diagnostics.connected()
                        try {
                            power.setup(g)
                        } catch (_: SecurityException) {
                            fail(1005)
                            return
                        }
                        setup =
                            scope.launch {
                                try {
                                    onLink(this@BleConnectionManager)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    if (active(g))
                                        fail((e as? GattFailure)?.status?.plus(1000) ?: -3)
                                }
                            }
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    if (active(g)) q.complete(key("discover"), status)
                }

                override fun onDescriptorWrite(
                    g: BluetoothGatt,
                    d: BluetoothGattDescriptor,
                    status: Int,
                ) {
                    if (active(g)) q.complete(key("descriptor", d.characteristic.uuid), status)
                }

                override fun onCharacteristicWrite(
                    g: BluetoothGatt,
                    c: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    if (active(g)) q.complete(key("write", c.uuid), status)
                }

                override fun onCharacteristicRead(
                    g: BluetoothGatt,
                    c: BluetoothGattCharacteristic,
                    value: ByteArray,
                    status: Int,
                ) {
                    if (active(g)) {
                        if (status == 0) diagnostics.bytes("rx_payload", value.size)
                        q.complete(key("read", c.uuid), status, value)
                    }
                }

                override fun onCharacteristicChanged(
                    g: BluetoothGatt,
                    c: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) {
                    if (active(g)) {
                        diagnostics.event("callbacks")
                        diagnostics.bytes("rx_payload", value.size)
                        onValue(c.uuid, value.copyOf())
                    }
                }

                override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                    if (active(g))
                        q.complete(
                            key("mtu"),
                            status,
                            byteArrayOf((mtu and 255).toByte(), (mtu shr 8).toByte()),
                        )
                }

                override fun onServiceChanged(g: BluetoothGatt) {
                    if (active(g)) onServiceChanged()
                }
            }
        gatt =
            device.connectGatt(
                context,
                passive,
                cb,
                BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK,
                Handler(Looper.getMainLooper()),
            )
        if (gatt == null) {
            fail(-4)
            return
        }
        if (!passive)
            timeout =
                scope.launch {
                    delay(30_000)
                    if (generations.accepts(generation)) fail(-1)
                }
    }

    suspend fun discover() {
        op("discover") { it.discoverServices() }
    }

    // Not used by default: all commands fit ATT's default 20-byte write payload, except optional
    // app-name lookups.
    suspend fun requestMtu(mtu: Int): Int {
        val bytes = op("mtu") { it.requestMtu(mtu.coerceIn(23, 185)) }
        return (bytes[0].toInt() and 255) or ((bytes[1].toInt() and 255) shl 8)
    }

    fun idle(lowPower: Boolean) {
        gatt?.let { power.idle(it, lowPower) }
    }

    private suspend fun op(
        type: String,
        uuid: UUID? = null,
        start: (BluetoothGatt) -> Boolean,
    ): ByteArray {
        val g = gatt ?: error("Disconnected")
        val q = queue ?: error("Disconnected")
        var startedAt: BridgeDiagnostics.OperationStamp? = null
        var success = false
        try {
            val result =
                q.run(key(type, uuid)) {
                    check(g === gatt)
                    startedAt = diagnostics.operationStarted(type)
                    start(g)
                }
            success = true
            return result
        } finally {
            diagnostics.operationFinished(type, startedAt, success)
        }
    }

    private fun characteristic(service: UUID, characteristic: UUID) =
        gatt?.getService(service)?.getCharacteristic(characteristic)
            ?: error("Characteristic unavailable")

    override fun has(service: UUID, characteristic: UUID) =
        gatt?.getService(service)?.getCharacteristic(characteristic) != null

    override suspend fun subscribe(
        service: UUID,
        characteristic: UUID,
        enabled: Boolean,
        indication: Boolean,
    ) {
        val c = characteristic(service, characteristic)
        val d = c.getDescriptor(CCCD) ?: error("CCCD unavailable")
        op("descriptor", characteristic) { g ->
            g.setCharacteristicNotification(c, enabled) &&
                g.writeDescriptor(
                    d,
                    if (!enabled) BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                    else if (indication) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                ) == BluetoothStatusCodes.SUCCESS
        }
    }

    override suspend fun write(service: UUID, characteristic: UUID, value: ByteArray) {
        val c = characteristic(service, characteristic)
        op("write", characteristic) {
            (it.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    BluetoothStatusCodes.SUCCESS)
                .also { accepted -> if (accepted) diagnostics.bytes("tx_payload", value.size) }
        }
    }

    override suspend fun writeIfCurrent(
        service: UUID,
        characteristic: UUID,
        value: ByteArray,
        valid: () -> Boolean,
    ): Boolean {
        val g = gatt ?: error("Disconnected")
        val q = queue ?: error("Disconnected")
        val c = characteristic(service, characteristic)
        var startedAt: BridgeDiagnostics.OperationStamp? = null
        var success = false
        try {
            val result =
                q.runIfCurrent(key("write", characteristic), valid) {
                    check(g === gatt)
                    startedAt = diagnostics.operationStarted("write")
                    (g.writeCharacteristic(
                            c,
                            value,
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                        ) == BluetoothStatusCodes.SUCCESS)
                        .also { accepted ->
                            if (accepted) diagnostics.bytes("tx_payload", value.size)
                        }
                }
            success = result != null
            if (result == null) diagnostics.event("gatt_stale_skipped")
            return result != null
        } finally {
            diagnostics.operationFinished("write", startedAt, success)
        }
    }

    override suspend fun read(service: UUID, characteristic: UUID): ByteArray {
        val c = characteristic(service, characteristic)
        return op("read", characteristic) { it.readCharacteristic(c) }
    }

    private fun fail(status: Int) {
        close()
        onLost(status)
    }

    fun close() {
        generations.next() // invalidate before cancelling pending operations
        timeout?.cancel()
        timeout = null
        setup?.cancel()
        setup = null
        queue?.close()
        queue = null
        val old = gatt
        gatt = null
        connected = false
        runCatching { old?.disconnect() }
        runCatching { old?.close() }
        diagnostics.disconnected()
        power = BlePowerController()
    }

    companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val GATT: UUID = UUID.fromString("00001801-0000-1000-8000-00805f9b34fb")
        val CHANGED: UUID = UUID.fromString("00002a05-0000-1000-8000-00805f9b34fb")
    }
}
