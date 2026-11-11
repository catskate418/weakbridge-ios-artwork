package dev.weakbridge.integration

/** Session-local notification content. Nothing in this model is written to disk. */
data class NotificationRecord(
    val uid: Long,
    val appId: String,
    val appName: String,
    val category: Int,
    val title: String,
    val subtitle: String,
    val message: String,
    val positiveLabel: String?,
    val negativeLabel: String?,
)

class NotificationStore(private val capacity: Int = MAX_MIRRORS) {
    enum class Change {
        ADDED,
        UPDATED,
        IDENTICAL,
    }

    data class Result(val change: Change, val evicted: Long?)

    private val records = linkedMapOf<Long, NotificationRecord>()

    init {
        require(capacity > 0)
    }

    val values: List<NotificationRecord>
        get() = records.values.toList()

    fun put(record: NotificationRecord): Result {
        val previous = records[record.uid]
        if (previous == record) return Result(Change.IDENTICAL, null)
        records[record.uid] = record
        val evicted =
            if (records.size > capacity) records.keys.first().also(records::remove) else null
        return Result(if (previous == null) Change.ADDED else Change.UPDATED, evicted)
    }

    fun remove(uid: Long) = records.remove(uid)

    fun clear() = records.clear()

    companion object {
        const val MAX_MIRRORS = 40
        // Leave two slots for the connection notification and media, below Android's usual 50 cap.
        const val MAX_GROUPS = 6

        fun groupedApps(records: List<NotificationRecord>): Set<String> =
            records
                .groupingBy { it.appId }
                .eachCount()
                .filterValues { it > 1 }
                .keys
                .take(MAX_GROUPS)
                .toSet()
    }
}

object NotificationQuietHours {
    /** The end hour is exclusive. Equal endpoints mean the entire day. */
    fun contains(enabled: Boolean, hour: Int, start: Int, end: Int): Boolean {
        if (!enabled || hour !in 0..23 || start !in 0..23 || end !in 0..23) return false
        if (start == end) return true
        return if (start < end) hour in start until end else hour >= start || hour < end
    }
}

/** Suppresses only the signal, never the notification or its content. Uses monotonic time. */
class NotificationAlertPolicy {
    private val lastAlert = linkedMapOf<String, Long>()

    fun shouldAlert(appId: String, now: Long, eligible: Boolean, cooldownSeconds: Int): Boolean {
        if (!eligible) return false
        val previous = lastAlert[appId]
        val cooldown = cooldownSeconds.coerceIn(0, 300) * 1000L
        if (previous != null && now - previous < cooldown) return false
        lastAlert.remove(appId)
        lastAlert[appId] = now
        if (lastAlert.size > 64) lastAlert.remove(lastAlert.keys.first())
        return true
    }

    fun clear() = lastAlert.clear()
}
