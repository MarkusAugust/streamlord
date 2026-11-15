package io.github.markusaugust.streamlord.test

import io.github.markusaugust.streamlord.core.DatastarEventValidationException
import io.github.markusaugust.streamlord.core.domain.DatastarResponse
import io.github.markusaugust.streamlord.core.domain.ElementNamespace
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.ElementsResponse
import io.github.markusaugust.streamlord.core.domain.PatchElements
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import io.github.markusaugust.streamlord.core.domain.ScriptResponse
import io.github.markusaugust.streamlord.core.domain.SignalsResponse
import io.github.markusaugust.streamlord.core.protocol.SseEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * An answer that is no stream, read as Datastar's client reads it (fetch.ts:609-650 in 1.0.5): by
 * its content type, with the options of the patch in the `datastar-*` headers.
 */
class DatastarAnswerTest {
    /** The answer as a server sends it, its headers looked up without regard to case. */
    private fun read(answer: DatastarResponse) = read(answer.body, mapOf("Content-Type" to answer.contentType) + answer.headers)

    private fun read(
        body: String,
        headers: Map<String, String>,
    ): DatastarEvents = datastarEvents(body) { name -> headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value }

    @Test
    fun `HTML is an element patch with the options its headers carry`() {
        val events =
            read(
                ElementsResponse(
                    "<li>Ashes</li>",
                    selector = "#feed",
                    mode = ElementPatchMode.APPEND,
                    useViewTransition = true,
                    viewTransitionSelector = "#feed",
                ),
            )
        events.assertExactly(
            PatchElements(
                "<li>Ashes</li>",
                selector = "#feed",
                mode = ElementPatchMode.APPEND,
                useViewTransition = true,
                viewTransitionSelector = "#feed",
            ),
        )
        read(ElementsResponse("<circle/>", selector = "#sky", mode = ElementPatchMode.APPEND, namespace = ElementNamespace.SVG))
            .assertPatchElements(namespace = ElementNamespace.SVG)
    }

    @Test
    fun `JSON is a signal patch, folded into the store as the client merges it`() {
        val events = read(SignalsResponse("""{"heads":13,"open":true}""", onlyIfMissing = true))
        events.assertPatchSignals(onlyIfMissing = true)
        events.assertSignal("heads", 13)
    }

    // The client appends the script to the head with the attributes of its header, and removes nothing.
    @Test
    fun `JavaScript is the script element the client appends to the head`() {
        read(ScriptResponse("go()", mapOf("type" to "module"))).assertExactly(
            PatchElements("""<script type="module">go()</script>""", selector = "head", mode = ElementPatchMode.APPEND),
        )
        // The script element as core writes one, whose end its text cannot close early.
        read(ScriptResponse("go('</script>')")).assertExactly(
            PatchElements("""<script>go('<\/script>')</script>""", selector = "head", mode = ElementPatchMode.APPEND),
        )
    }

    // Measured against the 1.0.5 client: Object.entries of what JSON.parse gives, and setAttribute, which
    // lowercases a name on an HTML element and makes each value the string JavaScript's String() makes.
    @Test
    fun `the script's attributes are what the client sets from its header`() {
        fun tag(header: String) =
            read("go()", mapOf("Content-Type" to "text/javascript", "datastar-script-attributes" to header))
                .assertPatchElements(selector = "head")
                .elements
        assertEquals("<script>go()</script>", tag("1"))
        assertEquals("<script>go()</script>", tag("[]"))
        assertEquals("""<script type="module">go()</script>""", tag("""{"Type":"module"}"""))
        assertEquals(
            """<script data-a="[object Object]" data-b="1,,x" data-c="1.5" data-d="true" data-e="null" data-f="1e-7">go()</script>""",
            tag("""{"data-a":{"x":1},"data-b":[1,null,"x"],"data-c":1.50,"data-d":true,"data-e":null,"data-f":0.0000001}"""),
        )
        assertEquals(
            """<script data-g="0.000001" data-h="0.00002" data-i="1e+21" data-j="-2.5">go()</script>""",
            tag("""{"data-g":1e-6,"data-h":2e-5,"data-i":1e21,"data-j":-2.50}"""),
        )
        for (refused in listOf("null", "\"ab\"", "[1]", "{")) {
            assertFailsWith<DatastarEventValidationException>(refused) { tag(refused) }
        }
    }

    @Test
    fun `a script of nothing but white space runs nothing`() {
        read("  ", mapOf("Content-Type" to "text/javascript")).assertNoEvents()
    }

    @Test
    fun `a stream, and any other content type, is read as a stream, as the client reads it`() {
        val wire = SseEncoder.encode(PatchSignals("""{"a":1}"""))
        read(wire, mapOf("Content-Type" to "text/event-stream")).assertSignal("a", 1)
        read(wire, mapOf("Content-Type" to "text/plain")).assertSignal("a", 1)
        read(wire, emptyMap()).assertSignal("a", 1)
    }

    // The client takes no header whose value is empty, and finds a type anywhere in the header, in the case it is written.
    @Test
    fun `an empty header is no option, and the type is found as the client finds it`() {
        read("<p id=\"p\">x</p>", mapOf("Content-Type" to "text/html", "datastar-selector" to "", "datastar-mode" to ""))
            .assertExactly(PatchElements("<p id=\"p\">x</p>"))
        read("""{"a":1}""", mapOf("Content-Type" to "application/json;charset=UTF-8")).assertSignal("a", 1)
        read("""{"a":1}""", mapOf("Content-Type" to "Application/JSON")).assertNoEvents()
    }

    @Test
    fun `an answer the client would refuse is reported`() {
        assertFailsWith<DatastarEventValidationException> {
            read("<p>x</p>", mapOf("Content-Type" to "text/html", "datastar-mode" to "sideways"))
        }
    }
}
