package dev.weakbridge.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import dev.weakbridge.BridgeApplication
import dev.weakbridge.R
import dev.weakbridge.service.BridgeForegroundService as Bridge

class MainActivity : ComponentActivity() {
    private val app
        get() = application as BridgeApplication

    private val permissions =
        arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    private var permissionRevision by mutableIntStateOf(0)
    private var pendingAction: Pair<String, String?>? = null
    private val request =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionRevision++
            Bridge.active?.applyConfiguration()
            val pending = pendingAction
            pendingAction = null
            if (
                pending != null &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                        PackageManager.PERMISSION_GRANTED
            ) {
                val required =
                    if (pending.first == Bridge.PAIR) Manifest.permission.BLUETOOTH_ADVERTISE
                    else if (pending.first == Bridge.SCAN) Manifest.permission.BLUETOOTH_SCAN
                    else Manifest.permission.BLUETOOTH_CONNECT
                if (checkSelfPermission(required) == PackageManager.PERMISSION_GRANTED)
                    send(pending.first, pending.second)
            }
        }
    private val discoverableRequest =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            // Android returns the approved duration; cancellation must never silently start
            // pairing.
            if (result.resultCode > 0) startBridgeService(Bridge.PAIR)
            else
                app.status.value =
                    app.status.value.copy(detail = getString(R.string.ui_visibility_cancelled))
        }

    override fun onResume() {
        super.onResume()
        permissionRevision++
        Bridge.active?.applyConfiguration()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Recheck permissions after returning from Android settings or a permission prompt.
            val bluetoothGranted =
                remember(permissionRevision) {
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                        PackageManager.PERMISSION_GRANTED
                }
            val notificationGranted =
                remember(permissionRevision) {
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED
                }
            BridgeWatchApp(
                app = app,
                bluetoothGranted = bluetoothGranted,
                notificationGranted = notificationGranted,
                onPermissions = { request.launch(permissions) },
                onAppSettings = ::openAppSettings,
                onAction = ::send,
            )
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName"),
                )
            )
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun send(action: String, address: String? = null) {
        val needed =
            when (action) {
                Bridge.PAIR ->
                    listOf(
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_ADVERTISE,
                    )
                Bridge.SCAN ->
                    listOf(
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN,
                    )
                else -> listOf(Manifest.permission.BLUETOOTH_CONNECT)
            }
        if (needed.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            pendingAction = action to address
            request.launch(permissions)
            return
        }
        if (action == Bridge.PAIR) {
            try {
                discoverableRequest.launch(
                    Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                        .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
                )
            } catch (_: ActivityNotFoundException) {
                app.status.value =
                    app.status.value.copy(detail = getString(R.string.ui_visibility_unavailable))
            } catch (_: SecurityException) {
                app.status.value =
                    app.status.value.copy(detail = getString(R.string.ui_visibility_permission))
            }
        } else startBridgeService(action, address)
    }

    private fun startBridgeService(action: String, address: String? = null) {
        startForegroundService(
            Intent(this, Bridge::class.java).setAction(action).putExtra("address", address)
        )
    }
}
