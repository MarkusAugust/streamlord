package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.application.Streamlord
import io.github.markusaugust.streamlord.core.domain.ElementNamespace
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.ElementsResponse
import io.github.markusaugust.streamlord.core.domain.PatchElements
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What Datastar's client does with the markup of an element patch (patchElements.ts), held where
 * the patch is made. Without a selector, `outer` and `replace` put each top-level element in place of
 * the page's element with its id, and skip one without an id with a console warning: judged by
 * `guardElements`, since the SDK specification's own tests send `<div>Merge</div>` so. In the svg or
 * mathml namespace the markup is parsed inside an `<svg>` or a `<math>` and only what stays inside it
 * is patched; in the html namespace an SVG or MathML element outside its own root is an HTML element
 * that draws nothing.
 */
class PatchMarkupJvmTest {
    private fun refused(
        why: String,
        make: () -> Any,
    ) {
        val failure = assertFailsWith<DatastarEventValidationException> { make() }
        assertTrue(why in failure.message.orEmpty(), failure.message)
    }

    private val guarded = Streamlord(guardElements = true)

    @Test
    fun `with the guard on and no selector, a top-level element without an id is refused, as the client skips it`() {
        refused("<p> has no id") { guarded.guard(PatchElements("<p>x</p>")) }
        refused("<p> has no id") { guarded.guard(PatchElements("<div id=\"a\">x</div><p>y</p>")) }
        refused("<li> has no id") { guarded.guard(PatchElements("<li id=\"\">x</li>", mode = ElementPatchMode.REPLACE)) }
        refused("<circle> has no id") { guarded.guard(PatchElements("<circle r=\"1\"/>", namespace = ElementNamespace.SVG)) }
        refused("<p> has no id") { guarded.guard(ElementsResponse("<p>x</p>")) }
        // The SDK specification's tests send it, so the event itself carries it.
        Streamlord.Default.guard(PatchElements("<div>Merge</div>"))
    }

    @Test
    fun `an element inside one with an id, a patch with a selector and a whole document need none`() {
        listOf(
            PatchElements("<div id=\"a\"><p>nested</p><br><img src=\"x\"></div>"),
            PatchElements("<div ID=\"a\">x</div>\n<!-- note -->\n<span id=\"b\"></span>"),
            PatchElements("<p>x</p>", selector = "#a"),
            PatchElements("<p>x</p>", selector = "#a", mode = ElementPatchMode.REPLACE),
            PatchElements("<p>x</p>", selector = "#a", mode = ElementPatchMode.APPEND),
            PatchElements("<body><p>x</p></body>"),
            PatchElements("<svg id=\"s\"><circle r=\"1\"/><g><rect/></g></svg>"),
            PatchElements("<circle id=\"c\" r=\"1\"/><rect id=\"r\"/>", namespace = ElementNamespace.SVG),
            PatchElements("<table id=\"t\"><tr><td>x</td></tr></table>"),
        ).forEach { guarded.guard(it) }
        guarded.guard(ElementsResponse("<p>x</p>", selector = "#a"))
    }

    @Test
    fun `in the svg or mathml namespace, an HTML element that leaves the root it is parsed in is refused`() {
        refused("<div> is HTML") {
            PatchElements("<div id=\"a\">x</div>", selector = "#s", mode = ElementPatchMode.INNER, namespace = ElementNamespace.SVG)
        }
        refused("<p> is HTML") {
            PatchElements("<g><p>x</p></g>", selector = "#s", mode = ElementPatchMode.APPEND, namespace = ElementNamespace.SVG)
        }
        refused("<div> is HTML") {
            PatchElements("<div>x</div>", selector = "#m", mode = ElementPatchMode.INNER, namespace = ElementNamespace.MATHML)
        }
        refused(
            "<b> is HTML",
        ) { ElementsResponse("<b>x</b>", selector = "#s", mode = ElementPatchMode.INNER, namespace = ElementNamespace.SVG) }
        PatchElements(
            "<foreignObject><div>x</div></foreignObject>",
            selector = "#s",
            mode = ElementPatchMode.INNER,
            namespace = ElementNamespace.SVG,
        )
        PatchElements("<mi><b>x</b></mi>", selector = "#m", mode = ElementPatchMode.INNER, namespace = ElementNamespace.MATHML)
        PatchElements("<circle></circle>", selector = "#s", mode = ElementPatchMode.INNER, namespace = ElementNamespace.SVG)
    }

    @Test
    fun `in the html namespace, an SVG or MathML element outside its root is refused`() {
        refused("<circle> is an SVG element") { PatchElements("<circle r=\"1\"/>", selector = "#s", mode = ElementPatchMode.APPEND) }
        refused("<rect> is an SVG element") { PatchElements("<div id=\"a\"><rect/></div>") }
        refused("<mfrac> is a MathML element") {
            PatchElements("<mfrac><mn>1</mn><mn>2</mn></mfrac>", selector = "#m", mode = ElementPatchMode.INNER)
        }
        PatchElements("<svg id=\"s\"><circle r=\"1\"/></svg>")
        PatchElements("<math id=\"m\"><mfrac><mn>1</mn><mn>2</mn></mfrac></math>")
        PatchElements("<g-chart id=\"c\"></g-chart>")
    }
}
