package com.example

import com.example.domain.model.TimeControl
import com.example.domain.model.TimeControlCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun timeControlPresets_containAllElevenRequiredControls() {
        assertEquals(11, TimeControl.AllPresets.size)
        val ids = TimeControl.AllPresets.map { it.id }
        assertEquals(
            listOf(
                "unlimited", "1+0", "2+1", "3+0", "3+2",
                "5+0", "5+3", "10+0", "10+5", "15+10", "30+0"
            ),
            ids
        )
    }

    @Test
    fun timeControlLookup_returnsExpectedPresetAndFallback() {
        val blitz = TimeControl.fromId("3+2")
        assertEquals(180L, blitz.initialSeconds)
        assertEquals(2L, blitz.incrementSeconds)
        assertEquals(TimeControlCategory.BLITZ, blitz.category)

        val unlimited = TimeControl.fromId("unlimited")
        assertTrue(unlimited.isUnlimited)

        val fallback = TimeControl.fromId("unknown")
        assertEquals(TimeControl.RAPID_10_0, fallback)
    }
}
