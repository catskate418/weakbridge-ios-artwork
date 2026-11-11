package dev.weakbridge

import dev.weakbridge.ams.displayable
import dev.weakbridge.ams.isExplicit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayableTest {
    @Test
    fun explicitMarkIsRemovedFromTheTitle() {
        assertEquals("Irkutsk Power", displayable("Irkutsk Power !\u20DD"))
        assertEquals("Black Water", displayable("Black Water !\u20DD"))
    }

    @Test
    fun loneEnclosingCircleIsRemovedToo() {
        assertEquals("Track", displayable("Track \u20DD"))
    }

    @Test
    fun explicitIsDetectedFromTheMark() {
        assertTrue(isExplicit("Irkutsk Power !\u20DD"))
        assertTrue(isExplicit("Track \u20DD"))
        assertFalse(isExplicit("Irkutsk Power"))
        assertFalse(isExplicit(""))
    }

    @Test
    fun variationSelectorsAreDropped() {
        assertEquals("Song", displayable("Song\uFE0F"))
    }

    @Test
    fun plainTextIsUntouched() {
        assertEquals("Земфира — Ромашки", displayable("Земфира — Ромашки"))
        assertEquals("Project n(o)va", displayable("Project n(o)va"))
    }

    @Test
    fun repeatedSpacesCollapse() {
        assertEquals("A B", displayable("A  B"))
    }
}
