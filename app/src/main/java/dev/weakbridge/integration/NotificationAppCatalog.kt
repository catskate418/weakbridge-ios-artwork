package dev.weakbridge.integration

import dev.weakbridge.R
import java.util.Locale

/** Names only; glyphs are drawn locally and do not use third-party logo assets. */
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

    private val abbreviations =
        mapOf(
            "mobilesms" to "SMS",
            "mobilemail" to "MAIL",
            "mobilephone" to "TEL",
            "messenger" to "MSGR",
            "instagram" to "INST",
            "whatsapp" to "WA",
            "telegraph" to "TG",
            "calendar" to "CAL",
            "reminders" to "REM",
            "discord" to "DISC",
        )

    /**
     * Short capitals drawn inside the per-app glyph. ANCS carries only the bundle identifier, so a
     * local glyph with these initials stands in for the app mark.
     */
    fun initials(appId: String, ancsName: String? = null): String {
        abbreviations[appId.substringAfterLast('.').lowercase(Locale.ROOT)]?.let {
            return it
        }
        names[appId]?.let { return words(it) }
        // A raw bundle identifier is not a name, so only the last segment is shown.
        return ancsName?.takeIf { it.isNotBlank() }?.let(::words)
            ?: appId.substringAfterLast('.').take(4).uppercase(Locale.ROOT).ifBlank { "IOS" }
    }

    private fun words(value: String): String {
        val tokens = value.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return "iOS"
        if (tokens.size == 1) return tokens[0].take(3).uppercase(Locale.ROOT)
        return tokens.take(3).joinToString("") { it.first().uppercase(Locale.ROOT) }
    }
}
