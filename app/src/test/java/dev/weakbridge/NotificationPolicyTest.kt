package dev.weakbridge

import dev.weakbridge.integration.NotificationAlertPolicy
import dev.weakbridge.integration.NotificationQuietHours
import dev.weakbridge.integration.NotificationRecord
import dev.weakbridge.integration.NotificationStore
import org.junit.Assert.*
import org.junit.Test

class NotificationPolicyTest {
    private fun record(uid: Long, app: String = "chat", text: String = "Message") =
        NotificationRecord(uid, app, app, 4, "Sender", "", text, null, "Clear")

    @Test
    fun duplicateProjectionDoesNotRequireAnotherNotification() {
        val store = NotificationStore()
        assertEquals(NotificationStore.Change.ADDED, store.put(record(1)).change)
        assertEquals(NotificationStore.Change.IDENTICAL, store.put(record(1)).change)
        assertEquals(NotificationStore.Change.UPDATED, store.put(record(1, text = "Edited")).change)
        assertEquals(1, store.values.size)
    }

    @Test
    fun contentBudgetEvictsOldestWithoutGrowingOnUpdates() {
        val store = NotificationStore(2)
        store.put(record(1))
        store.put(record(2))
        assertNull(store.put(record(1, text = "Edited")).evicted)
        assertEquals(1L, store.put(record(3)).evicted)
        assertEquals(listOf(2L, 3L), store.values.map { it.uid })
    }

    @Test
    fun manyDifferentAppsStillLeaveSlotsForMediaAndConnection() {
        val store = NotificationStore()
        (1L..100L).forEach { store.put(record(it, "app${it / 2}")) }
        assertEquals(40, store.values.size)
        val groups = NotificationStore.groupedApps(store.values)
        assertEquals(6, groups.size)
        assertTrue(store.values.size + groups.size + 2 <= 48)
    }

    @Test
    fun groupDisappearsWhenLastSiblingIsRemoved() {
        val store = NotificationStore()
        store.put(record(1))
        store.put(record(2))
        assertEquals(setOf("chat"), NotificationStore.groupedApps(store.values))
        store.remove(2)
        assertTrue(NotificationStore.groupedApps(store.values).isEmpty())
        store.clear()
        assertTrue(store.values.isEmpty())
    }

    @Test
    fun overnightQuietHoursRespectBothBoundaries() {
        assertTrue(NotificationQuietHours.contains(true, 22, 22, 8))
        assertTrue(NotificationQuietHours.contains(true, 0, 22, 8))
        assertTrue(NotificationQuietHours.contains(true, 7, 22, 8))
        assertFalse(NotificationQuietHours.contains(true, 8, 22, 8))
        assertFalse(NotificationQuietHours.contains(true, 21, 22, 8))
        assertFalse(NotificationQuietHours.contains(false, 23, 22, 8))
        assertTrue(NotificationQuietHours.contains(true, 5, 5, 5))
        assertTrue(NotificationQuietHours.contains(true, 12, 5, 5))
        assertFalse(NotificationQuietHours.contains(true, 23, -1, 8))
        assertTrue(NotificationQuietHours.contains(true, 11, 9, 12))
        assertFalse(NotificationQuietHours.contains(true, 12, 9, 12))
    }

    @Test
    fun cooldownSuppressesSignalButDoesNotConsumeFutureAlertForSilentUpdate() {
        val alerts = NotificationAlertPolicy()
        assertTrue(alerts.shouldAlert("chat", 1000, true, 3))
        assertFalse(alerts.shouldAlert("chat", 1500, true, 3))
        assertTrue(alerts.shouldAlert("mail", 1500, true, 3))
        assertFalse(alerts.shouldAlert("chat", 3900, false, 3))
        assertTrue(alerts.shouldAlert("chat", 4000, true, 3))
        alerts.clear()
        assertTrue(alerts.shouldAlert("chat", 4100, true, 3))
    }

    @Test
    fun suppressedSignalsDoNotDropNewMessages() {
        val alerts = NotificationAlertPolicy()
        val store = NotificationStore()
        (1L..10L).forEach {
            store.put(record(it))
            alerts.shouldAlert("chat", it * 10, true, 3)
        }
        assertEquals(10, store.values.size)
    }
}
