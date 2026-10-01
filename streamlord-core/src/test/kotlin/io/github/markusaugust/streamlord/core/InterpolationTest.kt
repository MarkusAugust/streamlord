package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.domain.Position
import io.github.markusaugust.streamlord.core.domain.Trusted
import io.github.markusaugust.streamlord.core.domain.UnsafeInterpolationException
import io.github.markusaugust.streamlord.core.domain.interpolate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InterpolationTest {
    /** What an attacker would send if the value reached the page unescaped. */
    private val hostile = """Gorvek" onmouseover="alert(1)" x="<script>alert(1)</script>"""

    @Test
    fun `a value in element text is escaped`() {
        assertEquals(
            "<li>&lt;script&gt;alert(1)&lt;/script&gt;</li>",
            interpolate("<li>%s</li>", "<script>alert(1)</script>"),
        )
    }

    @Test
    fun `a value in a quoted attribute cannot leave it`() {
        val out = interpolate("""<li title="%s">x</li>""", hostile)

        assertTrue('"' !in out.substringAfter("""title="""").substringBefore("""">"""), out)
        assertEquals(
            """<li title="Gorvek&quot; onmouseover=&quot;alert(1)&quot; x=&quot;&lt;script&gt;alert(1)&lt;/script&gt;">x</li>""",
            out,
        )
    }

    @Test
    fun `single quotes are escaped too, because an attribute may be written with them`() {
        assertEquals("<li title='it&#39;s'>x</li>", interpolate("<li title='%s'>x</li>", "it's"))
    }

    // The rule the page exists for: where it landed, not where it came from.
    @Test
    fun `a value in a Datastar expression attribute is refused`() {
        val attributes =
            listOf(
                """<li data-text="%s"></li>""",
                """<li data-on:click="@get('/x/%s')"></li>""",
                """<li data-signals="{name: '%s'}"></li>""",
                """<li data-star-text="%s"></li>""",
                """<li data-bind="%s"></li>""",
                """<li DATA-TEXT="%s"></li>""",
            )

        for (markup in attributes) {
            val failure = assertFailsWith<UnsafeInterpolationException>(markup) { interpolate(markup, "x") }
            assertEquals(Position.DATASTAR, failure.position, markup)
        }
    }

    @Test
    fun `a number or a boolean is admitted into a Datastar attribute`() {
        assertEquals("""<li data-signals="{n: 3}"></li>""", interpolate("""<li data-signals="{n: %s}"></li>""", 3))
        assertEquals("""<li data-show="true"></li>""", interpolate("""<li data-show="%s"></li>""", true))
        assertEquals("""<li data-signals="{n: 1.5}"></li>""", interpolate("""<li data-signals="{n: %s}"></li>""", 1.5))
    }

    @Test
    fun `an attribute that is not Datastar's is only escaped`() {
        assertEquals("""<li data-row="7"></li>""", interpolate("""<li data-row="%s"></li>""", 7))
        assertEquals("""<li class="a&amp;b"></li>""", interpolate("""<li class="%s"></li>""", "a&b"))
    }

    @Test
    fun `a value in the markup's own structure is refused`() {
        for (markup in listOf("""<li %s="x"></li>""", """<%s>x</%s>""", """<li class=%s></li>""")) {
            val values = List(Regex("%s").findAll(markup).count()) { "x" }
            val failure = assertFailsWith<UnsafeInterpolationException>(markup) { interpolate(markup, values) }
            assertEquals(Position.STRUCTURE, failure.position, markup)
        }
    }

    @Test
    fun `a value inside a script or a style is refused`() {
        for (markup in listOf("""<script>var x = "%s"</script>""", """<style>.a { color: %s }</style>""")) {
            val failure = assertFailsWith<UnsafeInterpolationException>(markup) { interpolate(markup, "x") }
            assertEquals(Position.CODE, failure.position, markup)
        }
    }

    @Test
    fun `a title or a textarea is text, not code`() {
        assertEquals("<title>&lt;b&gt;</title>", interpolate("<title>%s</title>", "<b>"))
        assertEquals("<textarea>&lt;b&gt;</textarea>", interpolate("<textarea>%s</textarea>", "<b>"))
    }

    @Test
    fun `Trusted is written as it stands, wherever it lands`() {
        assertEquals("<li><b>x</b></li>", interpolate("<li>%s</li>", Trusted("<b>x</b>")))
        assertEquals(
            "<li data-text=\"\$count\"></li>",
            interpolate("""<li data-text="%s"></li>""", Trusted("\$count")),
        )
    }

    @Test
    fun `the message names the attribute and what to do instead`() {
        val failure = assertFailsWith<UnsafeInterpolationException> { interpolate("""<li data-text="%s"></li>""", "x") }

        assertEquals("data-text", failure.attribute)
        assertEquals(0, failure.index)
        assertTrue(failure.message!!.contains("data-text"), failure.message!!)
        assertTrue(failure.message!!.contains("signal"), failure.message!!)
        assertTrue(failure.message!!.contains("Trusted"), failure.message!!)
    }

    @Test
    fun `the index names which value was refused`() {
        val failure =
            assertFailsWith<UnsafeInterpolationException> {
                interpolate("""<li class="%s">%s<b data-text="%s"></b></li>""", "a", "b", "c")
            }

        assertEquals(2, failure.index)
    }

    @Test
    fun `a miscounted hole fails here rather than quietly`() {
        assertFailsWith<IllegalArgumentException> { interpolate("<li>%s</li>") }
        assertFailsWith<IllegalArgumentException> { interpolate("<li>%s</li>", "a", "b") }
        assertTrue(
            assertFailsWith<IllegalArgumentException> { interpolate("<li></li>", "a") }
                .message!!
                .contains("0 %s holes and 1 value"),
        )
    }

    @Test
    fun `only a per cent and an s is a hole, so css and escaped urls need no ceremony`() {
        assertEquals("""<div style="width: 50%">x</div>""", interpolate("""<div style="width: 50%">%s</div>""", "x"))
        assertEquals("""<a href="/a%20b">x</a>""", interpolate("""<a href="/a%20b">%s</a>""", "x"))
    }

    @Test
    fun `two per cent signs write one`() {
        assertEquals("""<div>50% off x</div>""", interpolate("""<div>50%% off %s</div>""", "x"))
    }

    /** A value is data. Nothing in it is read back as markup, including its per cent signs. */
    @Test
    fun `a per cent sign in a value survives untouched`() {
        assertEquals("<li>100%% sure</li>", interpolate("<li>%s</li>", "100%% sure"))
    }

    @Test
    fun `markup with no holes is returned as it stands`() {
        assertEquals("<li>x</li>", interpolate("<li>x</li>"))
        assertEquals("", interpolate(""))
    }

    @Test
    fun `null is written as nothing`() {
        assertEquals("<li></li>", interpolate("<li>%s</li>", null))
    }

    @Test
    fun `a comment is not a tag, so a value in one is text`() {
        assertEquals("<!-- &lt;b&gt; --><li>x</li>", interpolate("<!-- %s --><li>x</li>", "<b>"))
    }
}
