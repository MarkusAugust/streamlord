package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.domain.ElementNamespace
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.ElementsResponse
import io.github.markusaugust.streamlord.core.domain.Html
import io.github.markusaugust.streamlord.core.domain.PatchElements
import io.github.markusaugust.streamlord.core.domain.interpolate
import io.github.markusaugust.streamlord.core.domain.joinToHtml
import kotlin.test.Test
import kotlin.test.assertEquals

class HtmlCompositionTest {
    private val rows = listOf("Gorvek", "<Merigall>").map { interpolate("<li>%s</li>", it) }

    @Test
    fun `joined markup is the markup of each, in order`() {
        assertEquals("<li>Gorvek</li><li>&lt;Merigall&gt;</li>", rows.joinToHtml().text)
    }

    @Test
    fun `a separator is markup between them and nowhere else`() {
        assertEquals("<li>Gorvek</li><hr><li>&lt;Merigall&gt;</li>", rows.joinToHtml(interpolate("<hr>")).text)
        assertEquals("", emptyList<Html>().joinToHtml(interpolate("<hr>")).text)
    }

    @Test
    fun `joined markup is markup in another interpolate`() {
        assertEquals("<ul><li>Gorvek</li><li>&lt;Merigall&gt;</li></ul>", interpolate("<ul>%s</ul>", rows.joinToHtml()).text)
    }

    @Test
    fun `a patch takes an Html as its elements`() {
        val html = interpolate("""<li id="hit">%s</li>""", "<b>")
        assertEquals(PatchElements(html.text), PatchElements(html))
        assertEquals(
            PatchElements(html.text, "#list", ElementPatchMode.APPEND, ElementNamespace.HTML, true, "#list", "7"),
            PatchElements(html, "#list", ElementPatchMode.APPEND, ElementNamespace.HTML, true, "#list", "7"),
        )
    }

    @Test
    fun `a response takes an Html as its elements`() {
        val html = interpolate("""<li id="hit">%s</li>""", "<b>")
        assertEquals(ElementsResponse(html.text), ElementsResponse(html))
        assertEquals(
            ElementsResponse(html.text, "#list", ElementPatchMode.APPEND, ElementNamespace.SVG, true),
            ElementsResponse(html, "#list", ElementPatchMode.APPEND, ElementNamespace.SVG, true),
        )
    }
}
