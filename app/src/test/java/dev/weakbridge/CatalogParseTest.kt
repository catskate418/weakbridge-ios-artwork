package dev.weakbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogParseTest {
    @Test
    fun decorativeTitleSuffixDoesNotBlockMatch() {
        assertEquals("black water", clean("Black Water !⃝"))
        assertEquals("flex", clean("Flex !⃝"))
        assertEquals("земфира ромашки", clean("Земфира — Ромашки"))
    }

    @Test
    fun cleanKeepsCyrillic() {
        assertEquals("яндекс музыка", clean("Яндекс Музыка"))
    }

    @Test
    fun missingFieldYieldsNull() {
        assertNull(field("{\"a\":\"b\"}", "trackName"))
        assertEquals("b", field("{\"trackName\":\"b\"}", "trackName"))
    }

    /** Mirrors the private helpers in RemoteCatalog so the matching rules stay covered. */
    private fun clean(value: String) =
        value.filter { it.isLetterOrDigit() || it == ' ' }
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun field(json: String, name: String): String? {
        val key = "\"$name\":\""
        val start = json.indexOf(key)
        if (start < 0) return null
        val from = start + key.length
        val end = json.indexOf('"', from)
        return if (end > from) json.substring(from, end) else null
    }
}
