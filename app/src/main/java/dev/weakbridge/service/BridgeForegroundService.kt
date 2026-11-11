package dev.weakbridge.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.content.*
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.weakbridge.*
import dev.weakbridge.ams.*
import dev.weakbridge.ancs.*
import dev.weakbridge.artwork.ArtworkService
import dev.weakbridge.ble.*
import dev.weakbridge.diagnostics.BridgeDiagnostics
import dev.weakbridge.integration.*
import dev.weakbridge.ui.MainActivity
import java.util.UUID
import kotlinx.coroutines.*

@SuppressLint("MissingPermission")
class BridgeForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app
        get() = application as BridgeApplication

    private lateinit var devices: BleDeviceRepository
    private lateinit var connection: BleConnectionManager
    private lateinit var diagnostics: BridgeDiagnostics
    private lateinit var notifications: NotificationBridge
    private lateinit var media: MediaSessionBridge
    private lateinit var artwork: ArtworkService
    private var ancs: AncsClient? = null
    private var ams: AmsClient? = null
    private var mediaCollector: Job? = null
    private var retry: Job? = null
    private var stable: Job? = null
    private var selection: Job? = null
    private var bond: CompletableDeferred<Unit>? = null
    private var bondingAddress: String? = null
    private val machine = ConnectionStateMachine()
    private var attempts = 0
    private var passive = false
    private var foregroundStarted = false
    private var receiverRegistered = false
    private var activeAddress: String? = null
    private var appliedMedia = true
    private var appliedLowPower = true
    private var appliedAutoReconnect = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        appliedMedia = app.settings.media
        appliedLowPower = app.settings.lowPower
        appliedAutoReconnect = app.settings.autoReconnect
        diagnostics = BridgeDiagnostics(app.settings)
        diagnostics.event("service_created")
        devices = BleDeviceRepository(this, scope, diagnostics)
        artwork = ArtworkService(this)
        notifications = NotificationBridge(this, artwork, diagnostics::event)
        media = MediaSessionBridge(this, { ams?.command(it) }, artwork, diagnostics::event)
        // A late cover republishes the card that was waiting for it.
        artwork.onCover = { _, bitmap -> scope.launch { media.applyCover(bitmap) } }
        // A downloaded icon repaints the cards that were showing initials.
        artwork.onIcon = { _, _ -> scope.launch { notifications.repaint() } }
        connection =
            BleConnectionManager(
                this,
                scope,
                diagnostics,
                ::initialize,
                ::onValue,
                ::lost,
                ::servicesChanged,
            )
        active = this
        registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            },
            Context.RECEIVER_EXPORTED,
        )
        receiverRegistered = true
    }

    private fun foreground() {
        if (foregroundStarted) return
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        startForeground(
            1,
            NotificationCompat.Builder(this, "bridge")
                .setSmallIcon(R.drawable.ic_bridge)
                .setContentTitle("WeakBridge")
                .setContentText(getString(R.string.core_foreground))
                .setContentIntent(open)
                .setOngoing(true)
                .setSilent(true)
                .build(),
        )
        foregroundStarted = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            status(ConnectionState.ERROR, getString(R.string.core_permission))
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            foreground()
        } catch (e: RuntimeException) {
            status(ConnectionState.ERROR, getString(R.string.core_open_app))
            stopSelf()
            return START_NOT_STICKY
        }
        diagnostics.event("service_commands")
        try {
            when (intent?.action) {
                DISCONNECT,
                FORGET -> {
                    app.settings.enabled = false
                    if (intent.action == FORGET) app.settings.address = null
                    stopAll()
                    status(ConnectionState.DISCONNECTED, getString(R.string.core_disconnected))
                    stopSelf()
                    return START_NOT_STICKY
                }
                SCAN -> {
                    app.settings.enabled = false
                    stopAll()
                    status(ConnectionState.SCANNING, getString(R.string.core_scanning))
                    devices.scan(
                        onEnd = {
                            status(
                                ConnectionState.DISCONNECTED,
                                getString(R.string.core_scan_ended),
                            )
                        },
                        onError = { status(ConnectionState.ERROR, it) },
                    )
                }
                PAIR -> {
                    app.settings.enabled = false
                    stopAll()
                    status(ConnectionState.BONDING, getString(R.string.core_pair_start))
                    devices.advertise(
                        onStatus = { status(ConnectionState.BONDING, it) },
                        onEnd = {
                            status(ConnectionState.DISCONNECTED, getString(R.string.core_pair_end))
                        },
                        onError = { status(ConnectionState.ERROR, it) },
                    )
                }
                BONDS -> {
                    devices.bonds()
                    if (
                        !app.settings.enabled &&
                            app.status.value.state !in
                                setOf(ConnectionState.BONDING, ConnectionState.SCANNING)
                    ) {
                        stopSelf()
                        return START_NOT_STICKY
                    }
                }
                CONNECT -> {
                    val selectedAddress = intent.getStringExtra("address")
                    val address = selectedAddress ?: app.settings.address
                    if (address != null) select(address, force = selectedAddress == null)
                    else status(ConnectionState.ERROR, getString(R.string.core_select_first))
                }
                CONFIG -> {
                    if (
                        !app.settings.enabled &&
                            app.status.value.state !in
                                setOf(ConnectionState.BONDING, ConnectionState.SCANNING)
                    ) {
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    applyConfiguration()
                }
                else ->
                    if (app.settings.enabled && app.settings.autoReconnect) reconnect()
                    else {
                        status(ConnectionState.DISCONNECTED, getString(R.string.core_connect_first))
                        stopSelf()
                        return START_NOT_STICKY
                    }
            }
        } catch (e: SecurityException) {
            stopAll()
            status(ConnectionState.ERROR, getString(R.string.core_permission))
        } catch (e: Exception) {
            stopAll()
            status(ConnectionState.ERROR, getString(R.string.core_bluetooth_retry))
        }
        return if (app.settings.enabled && app.settings.autoReconnect) START_STICKY
        else START_NOT_STICKY
    }

    private fun status(
        state: ConnectionState,
        detail: String,
        n: Boolean = false,
        m: Boolean = false,
    ) {
        machine.transition(state)
        diagnostics.event("state_${state.name.lowercase()}")
        app.status.value = BridgeStatus(state, detail, n, m)
    }

    private fun select(address: String, force: Boolean = false) {
        if (
            !force &&
                activeAddress == address &&
                app.status.value.state in
                    setOf(
                        ConnectionState.CONNECTING,
                        ConnectionState.BONDING,
                        ConnectionState.DISCOVERING_SERVICES,
                        ConnectionState.AUTHORIZING,
                        ConnectionState.SUBSCRIBING,
                        ConnectionState.CONNECTED,
                    )
        )
            return
        stopAll()
        activeAddress = address
        attempts = 0
        passive = false
        selection =
            scope.launch {
                try {
                    check(devices.adapter?.isEnabled == true)
                    val device = devices.device(address) ?: error("Device missing")
                    if (device.bondState != BluetoothDevice.BOND_BONDED) {
                        status(ConnectionState.BONDING, getString(R.string.core_confirm_pair))
                        bondingAddress = address
                        val pending = CompletableDeferred<Unit>()
                        bond = pending
                        if (device.bondState != BluetoothDevice.BOND_BONDING)
                            check(device.createBond())
                        withTimeout(90_000) { pending.await() }
                    }
                    devices.remember(device)
                    app.settings.enabled = true
                    status(ConnectionState.CONNECTING, device.name ?: "iPhone")
                    connection.connect(device, false)
                } catch (e: CancellationException) {
                    if (e is TimeoutCancellationException)
                        status(ConnectionState.ERROR, getString(R.string.core_pair_timeout))
                    else throw e
                } catch (e: Exception) {
                    status(ConnectionState.ERROR, getString(R.string.core_pair_failed))
                } finally {
                    bond = null
                    bondingAddress = null
                }
            }
    }

    private fun reconnect(force: Boolean = false) {
        val selected = devices.selected()
        if (selected == null) {
            status(ConnectionState.ERROR, getString(R.string.core_bond_unavailable))
            return
        }
        select(selected.address, force)
    }

    /** Local preferences and controller hints do not invalidate a healthy BLE session. */
    fun applyConfiguration() {
        diagnostics.configurationChanged()
        notifications.refreshSettings()
        val mediaChanged = appliedMedia != app.settings.media
        val priorityChanged = appliedLowPower != app.settings.lowPower
        val reconnectChanged = appliedAutoReconnect != app.settings.autoReconnect
        appliedMedia = app.settings.media
        appliedLowPower = app.settings.lowPower
        appliedAutoReconnect = app.settings.autoReconnect
        if (mediaChanged && app.settings.enabled) {
            // Change subscriptions as one fresh setup; never cancel an in-flight GATT read
            // and continue on the same potentially ambiguous response stream.
            if (!app.settings.media) media.update(MediaState())
            reconnect(force = true)
            return
        }
        if (priorityChanged && app.status.value.state == ConnectionState.CONNECTED)
            connection.idle(app.settings.lowPower)
        if (reconnectChanged && !app.settings.autoReconnect) {
            retry?.cancel()
            retry = null
            if (app.status.value.state == ConnectionState.RECONNECTING) {
                connection.close()
                closeProtocols()
                activeAddress = null
                status(ConnectionState.DISCONNECTED, getString(R.string.core_auto_off))
            }
        } else if (
            reconnectChanged &&
                app.settings.enabled &&
                app.status.value.state in setOf(ConnectionState.DISCONNECTED, ConnectionState.ERROR)
        ) {
            reconnect()
        }
    }

    private suspend fun initialize(transport: GattTransport) {
        status(ConnectionState.DISCOVERING_SERVICES, getString(R.string.core_discovering))
        connection.discover()
        if (transport.has(BleConnectionManager.GATT, BleConnectionManager.CHANGED))
            transport.subscribe(
                BleConnectionManager.GATT,
                BleConnectionManager.CHANGED,
                indication = true,
            )
        status(ConnectionState.AUTHORIZING, getString(R.string.core_sharing))
        val hasAncs =
            listOf(Ancs.SOURCE, Ancs.CONTROL, Ancs.DATA).all { transport.has(Ancs.SERVICE, it) }
        val hasAms =
            app.settings.media &&
                listOf(Ams.COMMAND, Ams.UPDATE).all { transport.has(Ams.SERVICE, it) }
        status(ConnectionState.SUBSCRIBING, getString(R.string.core_subscribing))
        if (hasAncs) {
            ancs =
                AncsClient(
                    scope = scope,
                    transport = transport,
                    allowed = app.settings::category,
                    publish = notifications::publish,
                    remove = notifications::remove,
                    fatal = { lost(-5) },
                    appAllowed = app.settings::appAllowed,
                    observeApp = { id, name ->
                        val entry =
                            NotificationApp(id, NotificationAppCatalog.displayName(id, name))
                        val existing = app.observedApps.value
                        if (existing.firstOrNull { it.id == id } != entry) {
                            val updated =
                                (existing.filterNot { it.id == id } + entry)
                                    .takeLast(128)
                                    .sortedBy { it.name.lowercase() }
                            app.observedApps.value = updated
                            app.settings.rememberObserved(updated)
                        }
                        // Warm the icon cache on first sight, so the card has the real mark the
                        // next time this app posts without waiting for a download.
                        artwork.requestIcon(id, entry.name)
                    },
                )
            ancs!!.start()
        }
        if (hasAms) {
            ams = AmsClient(scope, transport, { lost(-6) })
            mediaCollector = scope.launch { ams?.state?.collect { media.update(it) } }
            ams!!.start()
        }
        connection.idle(app.settings.lowPower)
        status(
            ConnectionState.CONNECTED,
            if (hasAncs || hasAms) getString(R.string.core_connected)
            else getString(R.string.core_services_missing),
            hasAncs,
            hasAms,
        )
        // Apps seen in earlier sessions get their icon warmed and their channel registered now,
        // so settings shows them and a card posted later never waits for a download.
        app.observedApps.value.forEach {
            notifications.ensureChannel(it.id, it.name)
            artwork.requestIcon(it.id, it.name)
        }
        // Tracks heard while the watch was offline get their covers now that it is connected.
        artwork.drainQueue()
        stable =
            scope.launch {
                delay(120_000)
                attempts = 0
                passive = false
            }
    }

    private fun onValue(uuid: UUID, bytes: ByteArray) {
        when (uuid) {
            Ancs.SOURCE -> {
                diagnostics.event("ancs_events")
                ancs?.source(bytes)
            }
            Ancs.DATA -> {
                diagnostics.event("ancs_fragments")
                ancs?.data(bytes)
            }
            Ams.UPDATE -> {
                diagnostics.event("ams_events")
                ams?.update(bytes)
            }
            Ams.COMMAND -> ams?.supported(bytes)
            BleConnectionManager.CHANGED -> servicesChanged()
        }
    }

    private fun servicesChanged() {
        // New generation rebuilds every subscription; no hidden BluetoothGatt.refresh API.
        if (app.status.value.state == ConnectionState.RECONNECTING) return
        lost(-7)
    }

    private fun closeProtocols() {
        stable?.cancel()
        stable = null
        mediaCollector?.cancel()
        mediaCollector = null
        ancs?.close()
        ancs = null
        ams?.close()
        ams = null
        notifications.clear()
        media.update(MediaState())
    }

    private fun lost(code: Int) {
        connection.close()
        closeProtocols()
        retry?.cancel()
        retry = null
        diagnostics.event("connection_drops")
        if (!app.settings.enabled) {
            status(ConnectionState.DISCONNECTED, getString(R.string.core_disconnected))
            return
        }
        if (
            code in setOf(1005, 1008, 1015)
        ) { // Authentication, encryption, authorization: user intervention, no retry storm.
            status(ConnectionState.ERROR, getString(R.string.core_authorization, code))
            return
        }
        if (!app.settings.autoReconnect) {
            status(ConnectionState.DISCONNECTED, getString(R.string.core_connection_lost, code))
            return
        }
        if (runCatching { devices.adapter?.isEnabled }.getOrNull() != true) {
            status(ConnectionState.DISCONNECTED, getString(R.string.core_waiting_bluetooth))
            return
        }
        if (passive) {
            status(ConnectionState.ERROR, getString(R.string.core_passive_failed, code))
            return
        }
        val delayMs = BleReconnectPolicy().delayMs(attempts++)
        status(
            ConnectionState.RECONNECTING,
            if (delayMs == null) getString(R.string.core_waiting_iphone)
            else getString(R.string.core_retry_delay, code, delayMs / 1000),
        )
        retry =
            scope.launch {
                try {
                    if (delayMs != null) delay(delayMs)
                    val selected =
                        devices.selected()
                            ?: run {
                                status(ConnectionState.ERROR, getString(R.string.core_bond_missing))
                                return@launch
                            }
                    diagnostics.event("reconnect_attempts")
                    passive = delayMs == null
                    connection.connect(selected, passive)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    status(ConnectionState.ERROR, getString(R.string.core_reconnect_failed))
                }
            }
    }

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                try {
                    when (intent.action) {
                        BluetoothAdapter.ACTION_STATE_CHANGED ->
                            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                                BluetoothAdapter.STATE_OFF,
                                BluetoothAdapter.STATE_TURNING_OFF -> {
                                    stopAll()
                                    status(
                                        ConnectionState.DISCONNECTED,
                                        getString(R.string.core_waiting_bluetooth),
                                    )
                                }
                                BluetoothAdapter.STATE_ON ->
                                    if (app.settings.enabled && app.settings.autoReconnect)
                                        reconnect()
                            }
                        BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                            devices.bonds()
                            val device =
                                intent.getParcelableExtra(
                                    BluetoothDevice.EXTRA_DEVICE,
                                    BluetoothDevice::class.java,
                                ) ?: return
                            if (device.address == bondingAddress)
                                when (device.bondState) {
                                    BluetoothDevice.BOND_BONDED -> bond?.complete(Unit)
                                    BluetoothDevice.BOND_NONE ->
                                        bond?.completeExceptionally(
                                            IllegalStateException("Bond failed")
                                        )
                                }
                            if (
                                device.address == app.settings.address &&
                                    device.bondState == BluetoothDevice.BOND_NONE
                            ) {
                                stopAll()
                                status(ConnectionState.ERROR, getString(R.string.core_bond_removed))
                            }
                        }
                    }
                } catch (e: SecurityException) {
                    stopAll()
                    status(ConnectionState.ERROR, getString(R.string.core_permission_revoked))
                }
            }
        }

    fun notificationAction(token: String?, uid: Long, positive: Boolean) {
        if (!notifications.accepts(token)) return
        scope.launch {
            try {
                if (notifications.accepts(token)) ancs?.action(uid, positive)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                diagnostics.event("action_failures")
            }
        }
    }

    fun notificationDismiss(token: String?, uid: Long) {
        if (notifications.accepts(token)) notifications.remove(uid)
    }

    fun notificationDismissGroup(token: String?, appId: String?) {
        if (notifications.accepts(token) && appId != null) notifications.removeGroup(appId)
    }

    fun mediaAction(token: String?, command: Int) {
        media.fromNotification(token, command)
    }

    fun diagnosticText() = diagnostics.snapshot(this)

    fun clearDiagnostics() {
        diagnostics.clear()
    }

    private fun stopAll() {
        activeAddress = null
        selection?.cancel()
        selection = null
        retry?.cancel()
        retry = null
        devices.stopDiscovery()
        connection.close()
        closeProtocols()
    }

    override fun onDestroy() {
        if (active === this) active = null
        stopAll()
        media.close()
        if (receiverRegistered) unregisterReceiver(receiver)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        var active: BridgeForegroundService? = null
            private set

        const val CONNECT = "connect"
        const val DISCONNECT = "disconnect"
        const val FORGET = "forget"
        const val SCAN = "scan"
        const val PAIR = "pair"
        const val BONDS = "bonds"
        const val CONFIG = "config"
    }
}
