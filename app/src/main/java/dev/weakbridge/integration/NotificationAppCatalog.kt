package dev.weakbridge.integration

import dev.weakbridge.R

/** Names only; category glyphs are drawn locally and do not use third-party logo assets. */
object NotificationAppCatalog {
    data class App(val id: String, val name: String)

    val knownApps =
        listOf(
            App("com.apple.MobileSMS", "Messages"),
            App("com.apple.mobilephone", "Phone"),
            App("com.apple.mobilemail", "Mail"),
            App("com.apple.mobilecal", "Calendar"),
            App("com.apple.reminders", "Reminders"),
            App("com.apple.facetime", "FaceTime"),
            App("com.apple.Maps", "Maps"),
            App("com.apple.Health", "Health"),
            App("com.apple.Music", "Music"),
            App("com.apple.mobilenotes", "Notes"),
            App("net.whatsapp.WhatsApp", "WhatsApp"),
            App("ph.telegra.Telegraph", "Telegram"),
            App("com.hammerandchisel.discord", "Discord"),
            App("com.facebook.Messenger", "Messenger"),
            App("com.burbn.instagram", "Instagram"),
            App("com.google.Gmail", "Gmail"),
            App("com.microsoft.Office.Outlook", "Outlook"),
            App("com.tinyspeck.chatlyio", "Slack"),
            App("com.microsoft.skype.teams", "Teams"),
            App("com.spotify.client", "Spotify"),
        )
    private val names = knownApps.associate { it.id to it.name }

    fun displayName(appId: String, ancsName: String? = null): String =
        ancsName?.takeIf { it.isNotBlank() }
            ?: names[appId]
            ?: appId.substringAfterLast('.').ifBlank { "iPhone" }

    fun icon(category: Int): Int =
        when (category) {
            1,
            2,
            3 -> R.drawable.ic_notification_call
            5 -> R.drawable.ic_notification_calendar
            6 -> R.drawable.ic_notification_mail
            8 -> R.drawable.ic_notification_health
            10 -> R.drawable.ic_notification_location
            11 -> R.drawable.ic_notification_music
            else -> R.drawable.ic_notification_message
        }
}
