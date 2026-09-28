package io.github.markusaugust.streamlord.demo

import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.PatchElements
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The gallery's claim is that each mode is aimed at the element it makes sense against, which
 * is a claim about selectors and can be checked here rather than by pressing eight buttons.
 */
class ModesTest {
    private fun patch(mode: String) = modeEvents(ModeSignals(mode)).first() as PatchElements

    @Test
    fun `the four that act on the stone, and the two that act on the wall`() {
        for (mode in listOf("inner", "outer", "replace", "before", "after", "remove")) {
            assertEquals("#stone", patch(mode).selector, "$mode should be aimed at the stone")
        }
        for (mode in listOf("prepend", "append")) {
            assertEquals("#wall", patch(mode).selector, "$mode puts elements inside the wall")
        }
    }

    @Test
    fun `remove is a selector and a mode, and carries nothing else`() {
        val remove = patch("remove")

        assertEquals(ElementPatchMode.REMOVE, remove.mode)
        assertNull(remove.elements, "a remove needs no elements, and sending some would be a lie")
    }

    @Test
    fun `the stone keeps its input in every mode that sends one`() {
        // The input is what makes morph and replace tell themselves apart on the page, so it
        // has to be in the markup of both.
        for (mode in listOf("inner", "outer", "replace")) {
            assertTrue(patch(mode).elements.orEmpty().contains("""id="chisel""""), "$mode dropped the input")
        }
    }

    @Test
    fun `an unknown mode falls back rather than failing`() {
        assertEquals(ElementPatchMode.INNER, patch("sideways").mode)
    }

    @Test
    fun `rebuilding the wall is itself a patch`() {
        val rebuild = patch("reset")

        assertEquals("#wall", rebuild.selector)
        assertEquals(ElementPatchMode.INNER, rebuild.mode)
        assertTrue(rebuild.elements.orEmpty().contains("""id="stone""""))
    }
}
