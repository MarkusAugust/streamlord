package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.domain.ElementsResponse
import io.github.markusaugust.streamlord.core.domain.PatchElements
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.core.json.JsonString
import io.github.markusaugust.streamlord.core.protocol.DatastarProtocol
import io.github.markusaugust.streamlord.core.protocol.SseDecoder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The header `datastar-view-transition-selector`, which the client reads from a `text/html` answer
 * from Datastar 1.0.5 on (plugins/actions/fetch.ts): the cases of
 * `conformance/dom/response-cases.json` whose `argsRaw` holds `viewTransitionSelector`, against
 * [ElementsResponse], which carries it as [PatchElements] does in a stream.
 */
class ElementsResponseViewTransitionSelectorTest {
    private val cases: List<JsonObject> =
        (JsonParser.parse(File("../conformance/dom/response-cases.json").readText()) as List<*>)
            .map { it as JsonObject }
            .filter { it.obj("argsRaw")!!.string("viewTransitionSelector") != null }

    private fun headers(case: JsonObject): Map<String, String> =
        case.obj("headers")!!.mapValues { (_, value) -> (value as JsonString).value }

    @Test
    fun `the protocol names the header`() {
        assertEquals("datastar-view-transition-selector", DatastarProtocol.Headers.VIEW_TRANSITION_SELECTOR)
        assertTrue(cases.all { DatastarProtocol.Headers.VIEW_TRANSITION_SELECTOR in headers(it) })
    }

    @Test
    fun `writes the selector as the header the client read it from`() {
        val case = cases.single { it.obj("argsRaw")!!.string("useViewTransition") == "true" }
        val args = case.obj("argsRaw")!!
        val response =
            ElementsResponse(
                args.string("elements")!!,
                useViewTransition = true,
                viewTransitionSelector = args.string("viewTransitionSelector"),
            )
        assertEquals(headers(case), response.headers)
    }

    /**
     * Without a view transition the client starts none and never uses the selector, so a selector
     * given alone is a mistake, refused here as in a stream; a decoded frame that carries one alone
     * comes back without it, as the client leaves it.
     */
    @Test
    fun `refuses the selector without a view transition, as a stream does`() {
        val case = cases.single { it.obj("argsRaw")!!.string("useViewTransition") == null }
        assertEquals(0, case.int("viewTransitions"))
        val args = case.obj("argsRaw")!!
        val selector = args.string("viewTransitionSelector")
        val failure =
            assertFailsWith<DatastarEventValidationException> { ElementsResponse(args.string("elements")!!, viewTransitionSelector = selector) }
        assertTrue("useViewTransition" in failure.message.orEmpty(), failure.message)
        assertFailsWith<DatastarEventValidationException> { PatchElements(args.string("elements"), viewTransitionSelector = selector) }
        val frame = "event: datastar-patch-elements\ndata: viewTransitionSelector $selector\ndata: elements ${args.string("elements")}\n\n"
        assertEquals(PatchElements(args.string("elements")), SseDecoder.decodeOne(frame))
    }

    /** A header arrives as written only in printable ASCII, the rule the answer's `selector` is held to. */
    @Test
    fun `refuses a selector a header cannot carry`() {
        assertFailsWith<DatastarEventValidationException> {
            ElementsResponse("<p id=\"a\">x</p>", useViewTransition = true, viewTransitionSelector = "#caf\u00e9")
        }
    }
}
