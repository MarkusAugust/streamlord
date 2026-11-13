package io.github.markusaugust.streamlord.spring

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `datastar-view-transition-selector`, which Datastar's client reads from a `text/html` answer from
 * 1.0.5 on (conformance/dom/response-cases.json): [datastarElements] takes the selector and sends it.
 */
class ElementsViewTransitionSelectorTest {
    @Test
    fun `an answer of elements carries the view transition's selector`() {
        val el = datastarElements("<li id=\"a\">x</li>", useViewTransition = true, viewTransitionSelector = "#list")
        assertEquals("true", el.headers.getFirst("datastar-use-view-transition"))
        assertEquals("#list", el.headers.getFirst("datastar-view-transition-selector"))
        assertEquals("<li id=\"a\">x</li>", el.body)
        // Without a view transition there is none to select an element for, and the client reads no selector.
        assertNull(datastarElements("<li id=\"a\">x</li>", viewTransitionSelector = "#list").headers.getFirst("datastar-view-transition-selector"))
    }
}
