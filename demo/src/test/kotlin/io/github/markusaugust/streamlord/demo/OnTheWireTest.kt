package io.github.markusaugust.streamlord.demo

import io.github.markusaugust.streamlord.core.domain.PatchElements
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The frames go only where there is a panel for them. Every page but the live one calls without
 * `wire`, and must get exactly what it got before the panel learned to follow every demo.
 */
class OnTheWireTest {
    @Test
    fun `without wire the events are left alone`() {
        val events = modeEvents(ModeSignals("outer"))

        assertEquals(events, events.withFrames(shown = false))
    }

    @Test
    fun `with wire one patch follows, and it carries the frames of the rest`() {
        val events = validationEvents(MusterSignals(banner = "The Ashen Vow", swords = "120"))
        val shown = events.withFrames(shown = true)

        assertEquals(events.size + 1, shown.size)
        val wire = shown.last() as PatchElements
        assertEquals("#wire", wire.selector)
        assertTrue(wire.elements.orEmpty().contains("datastar-patch-signals"))
        assertTrue(wire.elements.orEmpty().contains("selector #muster-summary"))
    }
}
