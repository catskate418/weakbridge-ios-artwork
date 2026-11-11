package dev.weakbridge.integration

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import dev.weakbridge.BridgeApplication
import dev.weakbridge.R
import android.graphics.Bitmap
import dev.weakbridge.ancs.AncsEvent
import dev.weakbridge.artwork.ArtworkService
import dev.weakbridge.service.BridgeForegroundService
import dev.weakbridge.ui.MainActivity
import java.time.LocalTime
import java.util.UUID

class NotificationBridge(
    private val context: Context,
    private val artwork: ArtworkService,
    private val recordEvent: (String) -> Unit = {},
) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val settings
        get() = (context.applicationContext as BridgeApplication).settings

    private val store = NotificationStore()
    private val alerts = NotificationAlertPolicy()
    private val rendered = mutableMapOf<Long, Projection>()
    private val summaries = mutableMapOf<String, Summary>()
    private val appChannels = mutableMapOf<String, String>()
    private val glyphs = NotificationGlyph()
    private var session = UUID.randomUUID().toString()
    private val openApp by lazy {
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private data class Projection(
        val record: NotificationRecord,
        val hidden: Boolean,
        val group: String?,
    )

    private data class Summary(val records: List<NotificationRecord>, val hidden: Boolean)

    init {
        listOf(
                "bridge" to R.string.notification_channel_connection,
                "media" to R.string.notification_channel_media,
            )
            .forEach { (id, name) ->
                manager.createNotificationChannel(
                    NotificationChannel(
                        id,
                        context.getString(name),
                        NotificationManager.IMPORTANCE_LOW,
                    )
                )
            }
        // Older releases created five shared category channels, and one build produced per-app ids
        // with the "app_" prefix doubled. Neither receives anything now, and leaving them behind
        // shows a confusing pile in the watch's settings. Only those exact patterns are removed:
        // a blanket sweep would also drop valid channels before they are registered again.
        manager.notificationChannels
            .map { it.id }
            .filter { id -> id.startsWith("iphone.") || id.startsWith("app.app_") }
            .forEach { manager.deleteNotificationChannel(it) }
        // Remove stale mirrors after process death; private content is kept only in this process.
        manager.activeNotifications
            .filter { it.tag == MIRROR_TAG || it.tag?.startsWith(GROUP_TAG) == true }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    /**
     * Wear OS settings list channels individually, so every iPhone application gets its own entry
     * instead of being merged into a shared category channel. Channels are created on first
     * contact and bounded: an unbounded pile of channels eventually breaks foreground service
     * notifications, so the oldest app loses its channel and falls back to a new one.
     */
    private fun appChannel(appId: String, appName: String): String {
        appChannels[appId]?.let {
            return it
        }
        // Bundle ids often start with "app." already, so the channel prefix is not repeated.
        val slug = appId.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        val base = "app." + slug.removePrefix("app_").take(40)
        var id = base
        var suffix = 2
        while (appChannels.containsValue(id)) id = base + "." + suffix++
        val channel =
            NotificationChannel(id, appName, NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                enableLights(true)
                description = context.getString(R.string.notification_channel_app_body, appName)
            }
        manager.createNotificationChannel(channel)
        appChannels[appId] = id
        if (appChannels.size > MAX_APP_CHANNELS) {
            val oldest = appChannels.keys.first()
            val oldId = appChannels.remove(oldest)
            if (oldId != null) manager.deleteNotificationChannel(oldId)
        }
        return id
    }

    /** Real app icon for the card body; null until it has been downloaded once. */
    private fun largeIcon(record: NotificationRecord): Bitmap? {
        if (record.appId == "iphone") return null
        return artwork.appIcon(record.appId) ?: run {
            artwork.requestIcon(record.appId, record.appName)
            null
        }
    }

    /**
     * Status-bar icon for a card: the app's real icon once downloaded, its initials before that.
     * The system tints small icons, so [NotificationGlyph] reduces the artwork to a readable
     * silhouette instead of handing over a full-colour square.
     */
    private fun smallIcon(record: NotificationRecord): IconCompat {
        artwork.appIcon(record.appId)?.let { icon -> return glyphs.fromAppIcon(icon) }
        if (record.appId != "iphone") artwork.requestIcon(record.appId, record.appName)
        return glyphs.initials(
            NotificationAppCatalog.initials(record.appId, record.appName),
            record.category,
        )
    }

    fun publish(event: AncsEvent, attributes: Map<Int, String>, name: String?) {
        val appId = attributes[0].orEmpty().ifBlank { "iphone" }
        val appName = NotificationAppCatalog.displayName(appId, name)
        val record =
            NotificationRecord(
                event.uid,
                appId,
                appName,
                event.category,
                attributes[1].orEmpty(),
                attributes[2].orEmpty(),
                attributes[3].orEmpty(),
                attributes[6]?.takeIf { event.supports(true) && it.isNotBlank() },
                attributes[7]?.takeIf { event.supports(false) && it.isNotBlank() },
            )
        val result = store.put(record)
        result.evicted?.let { uid ->
            rendered.remove(uid)
            manager.cancel(MIRROR_TAG, uid.toInt())
        }
        if (result.change == NotificationStore.Change.IDENTICAL) return
        val quiet =
            NotificationQuietHours.contains(
                settings.quietHoursEnabled,
                LocalTime.now().hour,
                settings.quietStartHour,
                settings.quietEndHour,
            )
        val eligible =
            result.change == NotificationStore.Change.ADDED &&
                event.type == 0 &&
                !event.preExisting &&
                event.flags and 1 == 0 &&
                !quiet &&
                settings.appAllowed(appId) &&
                settings.category(event.category) &&
                canPost()
        val alert =
            alerts.shouldAlert(
                appId,
                SystemClock.elapsedRealtime(),
                eligible,
                settings.alertCooldownSeconds,
            )
        render(if (alert) event.uid else null)
    }

    /**
     * Applies local options without reconnecting or reading ANCS again. All republished cards are
     * silent.
     */
    /**
     * Makes sure the application has its own channel before any of its cards exist, so the watch's
     * notification settings list every known source with its own switch.
     */
    fun ensureChannel(appId: String, appName: String) {
        if (appId.isBlank()) return
        appChannel(appId, appName)
    }

    /** Repaints existing cards silently, e.g. after an app icon finished downloading. */
    fun repaint() {
        if (!canPost()) return
        forgetRendered()
        render(null)
    }

    fun refreshSettings() {
        if (!canPost()) {
            forgetRendered()
            return
        }
        // Revoking notification permission removes cards outside our process. Reconcile only
        // during an explicit refresh, avoiding a Binder read for each incoming ANCS event.
        val active = manager.activeNotifications
        val mirrorIds = active.filter { it.tag == MIRROR_TAG }.mapTo(mutableSetOf()) { it.id }
        val groupTags = active.mapNotNullTo(mutableSetOf()) { it.tag }
        rendered.keys.removeAll { it.toInt() !in mirrorIds }
        summaries.keys.removeAll { GROUP_TAG + it !in groupTags }
        render(null)
    }

    fun accepts(token: String?) = token == session

    fun remove(uid: Long) {
        store.remove(uid)
        rendered.remove(uid)
        manager.cancel(MIRROR_TAG, uid.toInt())
        render(null)
    }

    fun clear() {
        rendered.keys.toList().forEach { manager.cancel(MIRROR_TAG, it.toInt()) }
        summaries.keys.toList().forEach { manager.cancel(GROUP_TAG + it, 0) }
        store.clear()
        rendered.clear()
        summaries.clear()
        alerts.clear()
        session = UUID.randomUUID().toString()
    }

    private fun canPost() =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun render(alertUid: Long?) {
        if (!canPost()) {
            forgetRendered()
            return
        }
        val records =
            store.values.filter { settings.appAllowed(it.appId) && settings.category(it.category) }
        val ids = records.mapTo(mutableSetOf()) { it.uid }
        (rendered.keys - ids).forEach {
            rendered.remove(it)
            manager.cancel(MIRROR_TAG, it.toInt())
        }
        val groups =
            if (settings.notificationsGrouped) NotificationStore.groupedApps(records)
            else emptySet()
        val hidden = settings.hideNotificationContent
        // Cancel old summaries first so a change of grouped applications cannot exceed the budget.
        (summaries.keys - groups).forEach {
            summaries.remove(it)
            manager.cancel(GROUP_TAG + it, 0)
        }
        for (record in records) {
            val projection = Projection(record, hidden, record.appId.takeIf { it in groups })
            if (rendered[record.uid] == projection) continue
            val title = if (hidden) record.appName else record.title.ifBlank { record.appName }
            val text =
                if (hidden) context.getString(R.string.notification_private_content)
                else record.message
            val expanded =
                if (hidden) text
                else
                    listOf(record.subtitle, record.message)
                        .filter { it.isNotBlank() }
                        .joinToString("\n")
            // The card shows the app's real icon while the status bar keeps a tinted silhouette,
            // which is the only two-slot combination the platform offers.
            val builder =
                NotificationCompat.Builder(context, appChannel(record.appId, record.appName))
                    .setSmallIcon(smallIcon(record))
                    .apply { largeIcon(record)?.let(::setLargeIcon) }
                    .setContentTitle(title)
                    .setSubText(record.appName)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
                    .setShowWhen(false)
                    .setOnlyAlertOnce(true)
                    .setSilent(record.uid != alertUid)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setCategory(category(record.category))
                    .setContentIntent(openApp)
                    .setDeleteIntent(intent(record.uid, "dismiss"))
                    .setPublicVersion(
                        NotificationCompat.Builder(context, appChannel(record.appId, record.appName))
                            .setSmallIcon(smallIcon(record))
                            .setContentTitle(record.appName)
                            .setContentIntent(openApp)
                            .setContentText(
                                context.getString(R.string.notification_private_content)
                            )
                            .build()
                    )
            projection.group?.let {
                builder
                    .setGroup(groupKey(it))
                    .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            }
            if (!hidden) {
                // Labels come from iOS. A positive ANCS action is not necessarily "answer call".
                record.positiveLabel?.let {
                    builder.addAction(0, it, intent(record.uid, "positive"))
                }
                record.negativeLabel?.let {
                    builder.addAction(0, it, intent(record.uid, "negative"))
                }
            }
            try {
                manager.notify(MIRROR_TAG, record.uid.toInt(), builder.build())
                recordEvent("notification_posts")
                rendered[record.uid] = projection
            } catch (_: SecurityException) {
                // Permission can be revoked between the check and delivery.
                forgetRendered()
                return
            }
        }
        for (appId in groups) {
            val children = records.filter { it.appId == appId }
            val summary = Summary(children, hidden)
            if (summaries[appId] == summary) continue
            val last = children.last()
            val count =
                context.resources.getQuantityString(
                    R.plurals.notification_group_count,
                    children.size,
                    children.size,
                )
            val style = NotificationCompat.InboxStyle().setSummaryText(count)
            if (!hidden)
                children.takeLast(5).forEach { child ->
                    style.addLine(
                        listOf(child.title, child.message)
                            .filter { it.isNotBlank() }
                            .joinToString(": ")
                    )
                }
            val builder =
                NotificationCompat.Builder(context, appChannel(last.appId, last.appName))
                    .setSmallIcon(smallIcon(last))
                    .setContentTitle(last.appName)
                    .setContentText(count)
                    .setStyle(style)
                    .setGroup(groupKey(appId))
                    .setGroupSummary(true)
                    .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                    .setSilent(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setContentIntent(openApp)
                    .setDeleteIntent(intent(0, "group", appId))
            try {
                manager.notify(GROUP_TAG + appId, 0, builder.build())
                recordEvent("notification_group_posts")
                summaries[appId] = summary
            } catch (_: SecurityException) {
                forgetRendered()
                return
            }
        }
    }

    private fun forgetRendered() {
        rendered.clear()
        summaries.clear()
    }

    fun removeGroup(appId: String) {
        store.values
            .filter { it.appId == appId }
            .forEach {
                store.remove(it.uid)
                rendered.remove(it.uid)
                manager.cancel(MIRROR_TAG, it.uid.toInt())
            }
        render(null)
    }

    private fun intent(uid: Long, operation: String, appId: String? = null): PendingIntent {
        val intent =
            Intent(context, NotificationActionReceiver::class.java).apply {
                action =
                    when (operation) {
                        "dismiss" -> "dev.weakbridge.DISMISS_NOTIFICATION"
                        "group" -> "dev.weakbridge.DISMISS_GROUP"
                        else -> "dev.weakbridge.ANCS_ACTION"
                    }
                data =
                    Uri.Builder()
                        .scheme("weakbridge")
                        .authority("notification")
                        .appendPath(session)
                        .appendPath(uid.toString())
                        .appendPath(operation)
                        .apply { appId?.let(::appendPath) }
                        .build()
                putExtra("uid", uid)
                putExtra("positive", operation == "positive")
                putExtra("session", session)
                putExtra("appId", appId)
            }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun category(category: Int) =
        when (category) {
            1,
            2,
            3 -> NotificationCompat.CATEGORY_CALL
            5 -> NotificationCompat.CATEGORY_EVENT
            6 -> NotificationCompat.CATEGORY_EMAIL
            else -> NotificationCompat.CATEGORY_MESSAGE
        }

    private fun groupKey(appId: String) = "dev.weakbridge.iphone.$appId"

    companion object {
        private const val MIRROR_TAG = "iphone"
        private const val GROUP_TAG = "iphone.group."
        private const val MAX_APP_CHANNELS = 40
    }
}

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Actions never restart a dead bridge or replay into a different connection session.
        val bridge = BridgeForegroundService.active ?: return
        val token = intent.getStringExtra("session")
        val uid = intent.getLongExtra("uid", -1)
        when (intent.action) {
            "dev.weakbridge.DISMISS_NOTIFICATION" -> bridge.notificationDismiss(token, uid)
            "dev.weakbridge.DISMISS_GROUP" ->
                bridge.notificationDismissGroup(token, intent.getStringExtra("appId"))
            "dev.weakbridge.ANCS_ACTION" ->
                bridge.notificationAction(token, uid, intent.getBooleanExtra("positive", false))
        }
    }
}
