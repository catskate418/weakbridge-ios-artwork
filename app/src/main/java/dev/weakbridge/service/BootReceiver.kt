package dev.weakbridge.service

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import dev.weakbridge.BridgeSettings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = BridgeSettings(context)
        if (
            !settings.enabled ||
                !settings.autoReconnect ||
                settings.address == null ||
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                    PackageManager.PERMISSION_GRANTED
        )
            return
        // connectedDevice is eligible at boot with runtime prerequisites; OEM restrictions still
        // apply.
        try {
            context.startForegroundService(Intent(context, BridgeForegroundService::class.java))
        } catch (_: IllegalStateException) {
            /* User can resume from launcher; no alarm workaround. */
        } catch (_: SecurityException) {
            /* Permission or platform policy changed. */
        }
    }
}
