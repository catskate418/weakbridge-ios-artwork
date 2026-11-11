package dev.weakbridge

import dev.weakbridge.integration.NotificationAppCatalog
import org.junit.Assert.assertEquals
import org.junit.Test

class AppGlyphLabelTest {
    @Test
    fun knownBundlesUseTheirAbbreviation() {
        assertEquals("WA", NotificationAppCatalog.initials("net.whatsapp.WhatsApp"))
        assertEquals("TG", NotificationAppCatalog.initials("ph.telegra.Telegraph"))
        assertEquals("SMS", NotificationAppCatalog.initials("com.apple.MobileSMS"))
        assertEquals("TEL", NotificationAppCatalog.initials("com.apple.mobilephone"))
    }

    @Test
    fun unknownBundlesFallBackToInitialsOfTheIdentifier() {
        assertEquals("IPHO", NotificationAppCatalog.initials("ru.avito.iphone"))
        assertEquals("IOS", NotificationAppCatalog.initials(""))
    }

    @Test
    fun ancsNameWinsWhenTheIdentifierIsUnknown() {
        assertEquals("ЯМ", NotificationAppCatalog.initials("ru.yandex.unknown", "Яндекс Музыка"))
        assertEquals("WA", NotificationAppCatalog.initials("net.whatsapp.WhatsApp", "WhatsApp"))
    }
}
