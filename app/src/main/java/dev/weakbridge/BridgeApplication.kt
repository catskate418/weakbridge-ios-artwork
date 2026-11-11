package dev.weakbridge

import android.app.Application
import android.content.Context
import dev.weakbridge.ble.BridgeStatus
import kotlinx.coroutines.flow.MutableStateFlow

class BridgeApplication : Application() {
    val status = MutableStateFlow(BridgeStatus())
    val observedApps = MutableStateFlow<List<NotificationApp>>(emptyList())
    val deviceListTitle = MutableStateFlow("Список устройств ещё не открыт")
    val devices = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    lateinit var settings: BridgeSettings

    override fun onCreate() {
        super.onCreate()
        settings = BridgeSettings(this)
        observedApps.value = settings.observed()
        status.value = BridgeStatus(detail = getString(R.string.core_ready))
        deviceListTitle.value = getString(R.string.core_devices_unopened)
    }
}

data class NotificationApp(val id: String, val name: String)

class BridgeSettings(context: Context) {
    private val prefs = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)

    private companion object {
        const val MAX_OBSERVED = 64
    }
    var address: String?
        get() = prefs.getString("address", null)
        set(value) {
            prefs.edit().putString("address", value).apply()
        }

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) {
            prefs.edit().putBoolean("enabled", value).apply()
        }

    var autoReconnect: Boolean
        get() = prefs.getBoolean("auto", true)
        set(value) {
            prefs.edit().putBoolean("auto", value).apply()
        }

    var media: Boolean
        get() = prefs.getBoolean("media", true)
        set(value) {
            prefs.edit().putBoolean("media", value).apply()
        }

    var diagnostics: Boolean
        get() = prefs.getBoolean("diagnostics", false)
        set(value) {
            prefs.edit().putBoolean("diagnostics", value).apply()
        }

    var lowPower: Boolean
        get() = prefs.getBoolean("lowPower", true)
        set(value) {
            prefs.edit().putBoolean("lowPower", value).apply()
        }

    var notificationsGrouped: Boolean
        get() = prefs.getBoolean("notifications_grouped", true)
        set(value) {
            prefs.edit().putBoolean("notifications_grouped", value).apply()
        }

    var hideNotificationContent: Boolean
        get() = prefs.getBoolean("hide_notification_content", false)
        set(value) {
            prefs.edit().putBoolean("hide_notification_content", value).apply()
        }

    var quietHoursEnabled: Boolean
        get() = prefs.getBoolean("quiet_hours", false)
        set(value) {
            prefs.edit().putBoolean("quiet_hours", value).apply()
        }

    var quietStartHour: Int
        get() = prefs.getInt("quiet_start", 22).coerceIn(0, 23)
        set(value) {
            prefs.edit().putInt("quiet_start", value.coerceIn(0, 23)).apply()
        }

    var quietEndHour: Int
        get() = prefs.getInt("quiet_end", 8).coerceIn(0, 23)
        set(value) {
            prefs.edit().putInt("quiet_end", value.coerceIn(0, 23)).apply()
        }

    var alertCooldownSeconds: Int
        get() = prefs.getInt("alert_cooldown", 3).coerceIn(0, 60)
        set(value) {
            prefs.edit().putInt("alert_cooldown", value.coerceIn(0, 60)).apply()
        }

    fun appAllowed(appId: String) =
        appId !in prefs.getStringSet("blocked_apps", emptySet()).orEmpty()

    fun setAppAllowed(appId: String, allowed: Boolean) {
        val blocked = prefs.getStringSet("blocked_apps", emptySet()).orEmpty().toMutableSet()
        if (allowed) blocked.remove(appId) else if (blocked.size < 256) blocked.add(appId)
        prefs.edit().putStringSet("blocked_apps", blocked).apply()
    }

    /**
     * Applications seen so far, kept as "bundleId\tbody" lines. Icons are warmed for this list on
     * every connection, which only works if the list outlives the process.
     */
    fun rememberObserved(apps: List<NotificationApp>) {
        val lines = apps.takeLast(MAX_OBSERVED).joinToString("\n") { "${it.id}\t${it.name}" }
        prefs.edit().putString("observed_apps", lines).apply()
    }

    fun observed(): MutableList<NotificationApp> =
        prefs.getString("observed_apps", "")
            .orEmpty()
            .lineSequence()
            .mapNotNull { line ->
                val id = line.substringBefore('\t').takeIf { it.isNotBlank() } ?: return@mapNotNull null
                NotificationApp(id, line.substringAfter('\t', id))
            }
            .toMutableList()

    fun category(id: Int) = prefs.getBoolean("category$id", true)

    fun setCategory(id: Int, value: Boolean) {
        prefs.edit().putBoolean("category$id", value).apply()
    }
}
