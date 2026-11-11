package dev.weakbridge

import dev.weakbridge.artwork.JsonCursor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonCursorTest {
    private val deezer = """
    {"data":[
      {"title":"Other","artist":{"name":"Other"},"album":{"cover_big":"https://a/1.jpg"}},
      {"title":"Black Water","artist":{"name":"FORTUNA 812"},"album":{"cover_big":"https://b/2.jpg"}}
    ]}
    """

    @Test
    fun readsNestedObjectsInOrder() {
        val entries = JsonCursor(deezer).objects("data")
        assertEquals(2, entries.size)
        assertEquals("Other", entries[0].string("title"))
        assertEquals("FORTUNA 812", entries[1].child("artist")?.string("name"))
        assertEquals("https://b/2.jpg", entries[1].child("album")?.string("cover_big"))
    }

    @Test
    fun ignoresKeysThatOnlyAppearInsideStrings() {
        val json = """{"note":"\"title\":\"decoy\"","title":"real"}"""
        assertEquals("real", JsonCursor(json).string("title"))
    }

    @Test
    fun missingKeyIsNull() {
        assertNull(JsonCursor("""{"a":"b"}""").string("title"))
        assertNull(JsonCursor("""{"a":"b"}""").child("album"))
    }

    @Test
    fun escapedQuotesDoNotBreakStringBounds() {
        val json = """{"title":"He said \"hi\"","x":1}"""
        assertEquals("He said \"hi\"", JsonCursor(json).string("title"))
    }

    @Test
    fun arrayOfScalarsIsNotTreatedAsObjects() {
        assertEquals(0, JsonCursor("""{"data":["a","b"]}""").objects("data").size)
    }
}
