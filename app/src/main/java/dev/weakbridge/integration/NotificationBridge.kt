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
import dev.weakbridge.BridgeApplication
import dev.weakbridge.R
import dev.weakbridge.ancs.AncsEvent
import dev.weakbridge.service.BridgeForegroundService
import dev.weakbridge.ui.MainActivity
import java.time.LocalTime
import java.util.UUID

class NotificationBridge(
    private val context: Context,
    private val recordEvent: (String) -> Unit = {},
) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val settings
        get() = (context.applicationContext as BridgeApplication).settings

    private val store = NotificationStore()
    private val alerts = NotificationAlertPolicy()
    private val rendered = mutableMapOf<Long, Projection>()
    private val summaries = mutableMapOf<String, Summary>()
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
                "iphone.messages" to R.string.notification_channel_messages,
                "iphone.calls" to R.string.notification_channel_calls,
                "iphone.mail" to R.string.notification_channel_mail,
                "iphone.events" to R.string.notification_channel_events,
                "iphone.other" to R.string.notification_channel_other,
                "bridge" to R.string.notification_channel_connection,
                "media" to R.string.notification_channel_media,
            )
            .forEach { (id, name) ->
                val channel =
                    NotificationChannel(
                        id,
                        context.getString(name),
                        if (id == "bridge" || id == "media") NotificationManager.IMPORTANCE_LOW
                        else NotificationManager.IMPORTANCE_DEFAULT,
                    )
                if (id.startsWith("iphone.")) channel.enableVibration(true)
                manager.createNotificationChannel(channel)
            }
        // Remove stale mirrors after process death; private content is kept only in this process.
        manager.activeNotifications
            .filter { it.tag == MIRROR_TAG || it.tag?.startsWith(GROUP_TAG) == true }
            .forEach { manager.cancel(it.tag, it.id) }
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
            val builder =
                NotificationCompat.Builder(context, channel(record.category))
                    .setSmallIcon(NotificationAppCatalog.icon(record.category))
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
                        NotificationCompat.Builder(context, channel(record.category))
                            .setSmallIcon(NotificationAppCatalog.icon(record.category))
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
                NotificationCompat.Builder(context, channel(last.category))
                    .setSmallIcon(NotificationAppCatalog.icon(last.category))
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

    private fun channel(category: Int) =
        when (category) {
            1,
            2,
            3 -> "iphone.calls"
            4 -> "iphone.messages"
            5 -> "iphone.events"
            6 -> "iphone.mail"
            else -> "iphone.other"
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
