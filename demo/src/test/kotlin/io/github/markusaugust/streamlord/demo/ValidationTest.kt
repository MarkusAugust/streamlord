package io.github.markusaugust.streamlord.demo

import io.github.markusaugust.streamlord.core.domain.PatchElements
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rules are a pure function from signals to faults, which is the argument the demo makes:
 * validation that lives on the server can be tested without a browser, a form or a network.
 */
class ValidationTest {
    @Test
    fun `an empty form faults on both fields`() {
        val faults = faults(MusterSignals())

        assertEquals(listOf("banner", "swords"), faults.map { it.field })
    }

    @Test
    fun `a banner is three letters or more, and letters at that`() {
        assertEquals("banner", faults(MusterSignals(banner = "Ox", swords = "1")).single().field)
        assertTrue(faults(MusterSignals(banner = "Oxen Vow", swords = "1")).isEmpty())
        assertTrue(
            faults(MusterSignals(banner = "Oxen 7", swords = "1")).single().says.contains("Letters and spaces"),
        )
    }

    @Test
    fun `swords are digits, and there is at least one of them`() {
        assertTrue(faults(MusterSignals(banner = "Oxen Vow", swords = "many")).single().says.contains("digits"))
        assertTrue(faults(MusterSignals(banner = "Oxen Vow", swords = "0")).single().says.contains("bedsheet"))
        assertTrue(faults(MusterSignals(banner = "Oxen Vow", swords = "1000")).single().says.contains("armies"))
    }

    @Test
    fun `the answer is signals for the fields and markup for the summary`() {
        val events = validationEvents(MusterSignals(banner = "Ox", swords = "2"))

        val signals = (events[0] as PatchSignals).signals
        assertTrue(signals.contains(""""bannerFault": "Three letters"""))
        assertTrue(signals.contains(""""swordsFault": """"), "a field with no fault is cleared, not left alone")
        assertTrue(signals.contains(""""mustered": false"""))

        val summary = (events[1] as PatchElements)
        assertEquals("#muster-summary", summary.selector)
        assertTrue(summary.elements.orEmpty().contains("fs-error-summary"))
        assertTrue(summary.elements.orEmpty().contains("One thing is wrong"))
    }

    @Test
    fun `a form that passes says what it mustered`() {
        val events = validationEvents(MusterSignals(banner = "Oxen Vow", swords = "120"))

        assertTrue((events[0] as PatchSignals).signals.contains(""""mustered": true"""))
        assertTrue((events[1] as PatchElements).elements.orEmpty().contains("musters 120 swords"))
    }
}
