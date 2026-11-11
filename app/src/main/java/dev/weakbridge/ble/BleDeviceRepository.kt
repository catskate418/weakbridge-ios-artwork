package dev.weakbridge.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import dev.weakbridge.BridgeApplication
import dev.weakbridge.R
import dev.weakbridge.ancs.Ancs
import dev.weakbridge.diagnostics.BridgeDiagnostics
import kotlinx.coroutines.*

/** Persist only an OS bond reference. Android resolves bonded peer privacy addresses. */
@SuppressLint("MissingPermission")
class BleDeviceRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val diagnostics: BridgeDiagnostics,
) {
    private val app = context.applicationContext as BridgeApplication
    private val manager = context.getSystemService(BluetoothManager::class.java)
    val adapter: BluetoothAdapter?
        get() = manager.adapter

    private var scan: ScanCallback? = null
    private var expiry: Job? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertising: AdvertiseCallback? = null
    private var server: BluetoothGattServer? = null
    private var discoveryEpoch = 0L
    private val found = linkedMapOf<String, String>()

    fun bonds() {
        app.deviceListTitle.value = context.getString(R.string.core_saved_devices)
        found.clear()
        adapter?.bondedDevices?.forEach {
            found[it.address] = it.name ?: context.getString(R.string.core_saved_device)
        }
        publish()
    }

    fun selected(): BluetoothDevice? =
        adapter?.bondedDevices?.firstOrNull { it.address == app.settings.address }

    fun device(address: String): BluetoothDevice? = adapter?.getRemoteDevice(address)

    fun remember(device: BluetoothDevice) {
        require(device.bondState == BluetoothDevice.BOND_BONDED)
        app.settings.address = device.address
    }

    private fun publish() {
        app.devices.value = found.map { it.key to it.value }
    }

    fun scan(onEnd: () -> Unit, onError: (String) -> Unit) {
        stopDiscovery()
        found.clear()
        publish()
        app.deviceListTitle.value = context.getString(R.string.core_found_devices)
        val epoch = discoveryEpoch
        val scanner =
            adapter?.bluetoothLeScanner ?: error(context.getString(R.string.core_enable_bluetooth))
        // Callback identity is checked against the active scan to reject delayed results.
        val actual =
            object : ScanCallback() {
                override fun onScanResult(type: Int, result: ScanResult) {
                    val owner = this
                    scope.launch {
                        if (scan !== owner) return@launch
                        found[result.device.address] =
                            result.device.name ?: context.getString(R.string.core_ancs_device)
                        publish()
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    scope.launch {
                        if (epoch == discoveryEpoch) {
                            stopDiscovery()
                            onError(context.getString(R.string.core_scan_error, errorCode))
                        }
                    }
                }
            }
        scan = actual
        diagnostics.scan(true)
        scanner.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(Ancs.SERVICE)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(),
            actual,
        )
        expiry =
            scope.launch {
                delay(20_000)
                stopDiscovery()
                onEnd()
            }
    }

    /**
     * iOS commonly discovers an ANCS consumer by service solicitation, not by advertising ANCS
     * itself.
     */
    fun advertise(onStatus: (String) -> Unit, onEnd: () -> Unit, onError: (String) -> Unit) {
        stopDiscovery()
        val epoch = discoveryEpoch
        val bluetooth = adapter ?: error(context.getString(R.string.core_no_bluetooth))
        if (!bluetooth.isEnabled) {
            onError(context.getString(R.string.core_watch_bluetooth))
            return
        }
        val name = bluetooth.name.orEmpty()
        val layout = PairingAdvertising.layout(name)
        if (layout == PairingAdvertising.Layout.NAME_TOO_LONG) {
            onError(context.getString(R.string.core_long_name))
            return
        }
        val adv =
            bluetooth.bluetoothLeAdvertiser
                ?: run {
                    onError(context.getString(R.string.core_no_advertising))
                    return
                }
        server =
            manager.openGattServer(
                context,
                object : BluetoothGattServerCallback() {
                    override fun onConnectionStateChange(
                        device: BluetoothDevice,
                        status: Int,
                        newState: Int,
                    ) {
                        scope.launch {
                            if (epoch != discoveryEpoch || advertising == null) return@launch
                            if (status != 0) {
                                onStatus(context.getString(R.string.core_incoming_error, status))
                                return@launch
                            }
                            if (newState == BluetoothProfile.STATE_CONNECTED) {
                                onStatus(context.getString(R.string.core_incoming_pair))
                                try {
                                    if (
                                        device.bondState == BluetoothDevice.BOND_NONE &&
                                            !device.createBond()
                                    )
                                        onStatus(context.getString(R.string.core_pair_retry))
                                } catch (_: SecurityException) {
                                    onError(context.getString(R.string.core_nearby))
                                }
                            }
                        }
                    }
                },
            )
                ?: run {
                    onError(context.getString(R.string.core_pair_server))
                    return
                }
        val cb =
            object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                    scope.launch {
                        if (epoch == discoveryEpoch)
                            onStatus(context.getString(R.string.core_advertising, name))
                    }
                }

                override fun onStartFailure(errorCode: Int) {
                    scope.launch {
                        if (epoch == discoveryEpoch) {
                            stopDiscovery()
                            onError(PairingAdvertising.error(errorCode))
                        }
                    }
                }
            }
        advertiser = adv
        advertising = cb
        // Keep the unchanged system name in the primary packet so discovery UIs can see it.
        // Legacy packets have 31 bytes. Longer names move solicitation to the scan response.
        val primary = AdvertiseData.Builder().setIncludeDeviceName(true)
        val response = AdvertiseData.Builder()
        if (layout == PairingAdvertising.Layout.COMBINED)
            primary.addServiceSolicitationUuid(ParcelUuid(Ancs.SERVICE))
        else response.addServiceSolicitationUuid(ParcelUuid(Ancs.SERVICE))
        adv.startAdvertising(
            AdvertiseSettings.Builder()
                .setConnectable(true)
                .setTimeout(120_000)
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .build(),
            primary.build(),
            response.build(),
            cb,
        )
        expiry =
            scope.launch {
                delay(120_000)
                stopDiscovery()
                bonds()
                onEnd()
            }
    }

    fun stopDiscovery() {
        discoveryEpoch++
        expiry?.cancel()
        expiry = null
        scan?.let { runCatching { adapter?.bluetoothLeScanner?.stopScan(it) } }
        scan = null
        diagnostics.scan(false)
        advertising?.let { runCatching { advertiser?.stopAdvertising(it) } }
        advertising = null
        advertiser = null
        runCatching { server?.close() }
        server = null
    }
}
