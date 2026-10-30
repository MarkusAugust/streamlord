package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.domain.ElementsGuard
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
            render("<li>%s</li>", "<script>alert(1)</script>"),
        )
    }

    @Test
    fun `a value in a quoted attribute cannot leave it`() {
        val out = render("""<li title="%s">x</li>""", hostile)

        assertTrue('"' !in out.substringAfter("""title="""").substringBefore("""">"""), out)
        assertEquals(
            """<li title="Gorvek&quot; onmouseover=&quot;alert(1)&quot; x=&quot;&lt;script&gt;alert(1)&lt;/script&gt;">x</li>""",
            out,
        )
    }

    @Test
    fun `single quotes are escaped too, because an attribute may be written with them`() {
        assertEquals("<li title='it&#39;s'>x</li>", render("<li title='%s'>x</li>", "it's"))
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
                """<li DATA-TEXT="%s"></li>""",
                """<li data-json-signals="%s"></li>""",
                """<li data-persist="%s"></li>""",
                // A signal path, which the client follows without a guard: `__proto__.x` would reach every object.
                """<li data-bind="%s"></li>""",
                """<li data-ref="%s"></li>""",
                """<li data-indicator="%s"></li>""",
            )

        for (markup in attributes) {
            val failure = assertFailsWith<UnsafeInterpolationException>(markup) { render(markup, "x") }
            assertEquals(Position.DATASTAR, failure.position, markup)
            assertTrue("reads as an expression or follows as a signal path" in failure.message!!, failure.message!!)
        }
    }

    // The client reads these as they stand (csp.ts, patchElements.ts, engine.ts), so escaping holds.
    @Test
    fun `a value in a Datastar attribute the client does not evaluate is escaped and admitted`() {
        val attributes =
            listOf(
                """<html data-nonce="%s"></html>""",
                """<html data-star-nonce="%s"></html>""",
                """<li data-preserve-attr="%s"></li>""",
                """<li data-ignore="%s"></li>""",
                """<li data-ignore-morph="%s"></li>""",
            )
        for (markup in attributes) {
            assertEquals(markup.replace("%s", "a&quot;b"), render(markup, "a\"b"), markup)
        }
    }

    @Test
    fun `a number or a boolean is admitted into a Datastar attribute`() {
        assertEquals("""<li data-signals="{n: 3}"></li>""", render("""<li data-signals="{n: %s}"></li>""", 3))
        assertEquals("""<li data-show="true"></li>""", render("""<li data-show="%s"></li>""", true))
        assertEquals("""<li data-signals="{n: 1.5}"></li>""", render("""<li data-signals="{n: %s}"></li>""", 1.5))
    }

    @Test
    fun `an attribute that is not Datastar's is only escaped`() {
        assertEquals("""<li data-row="7"></li>""", render("""<li data-row="%s"></li>""", 7))
        assertEquals("""<li class="a&amp;b"></li>""", render("""<li class="%s"></li>""", "a&b"))
    }

    @Test
    fun `a value in the markup's own structure is refused`() {
        for (markup in listOf("""<li %s="x"></li>""", """<%s>x</%s>""", """<li class=%s></li>""")) {
            val values = List(Regex("%s").findAll(markup).count()) { "x" }
            val failure = assertFailsWith<UnsafeInterpolationException>(markup) { render(markup, values) }
            assertEquals(Position.STRUCTURE, failure.position, markup)
        }
    }

    @Test
    fun `a value inside a script or a style is refused`() {
        for (markup in listOf("""<script>var x = "%s"</script>""", """<style>.a { color: %s }</style>""")) {
            val failure = assertFailsWith<UnsafeInterpolationException>(markup) { render(markup, "x") }
            assertEquals(Position.CODE, failure.position, markup)
        }
    }

    @Test
    fun `a title or a textarea is text, not code`() {
        assertEquals("<title>&lt;b&gt;</title>", render("<title>%s</title>", "<b>"))
        assertEquals("<textarea>&lt;b&gt;</textarea>", render("<textarea>%s</textarea>", "<b>"))
    }

    @Test
    fun `Trusted is written as it stands, wherever it lands`() {
        assertEquals("<li><b>x</b></li>", render("<li>%s</li>", Trusted("<b>x</b>")))
        assertEquals(
            "<li data-text=\"\$count\"></li>",
            render("""<li data-text="%s"></li>""", Trusted("\$count")),
        )
    }

    @Test
    fun `the message names the attribute and what to do instead`() {
        val failure = assertFailsWith<UnsafeInterpolationException> { render("""<li data-text="%s"></li>""", "x") }

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
                render("""<li class="%s">%s<b data-text="%s"></b></li>""", "a", "b", "c")
            }

        assertEquals(2, failure.index)
    }

    @Test
    fun `a miscounted hole fails here rather than quietly`() {
        assertFailsWith<IllegalArgumentException> { render("<li>%s</li>") }
        assertFailsWith<IllegalArgumentException> { render("<li>%s</li>", "a", "b") }
        assertTrue(
            assertFailsWith<IllegalArgumentException> { render("<li></li>", "a") }
                .message!!
                .contains("0 %s holes and 1 value"),
        )
    }

    @Test
    fun `only a per cent and an s is a hole, so css and escaped urls need no ceremony`() {
        assertEquals("""<div style="width: 50%">x</div>""", render("""<div style="width: 50%">%s</div>""", "x"))
        assertEquals("""<a href="/a%20b">x</a>""", render("""<a href="/a%20b">%s</a>""", "x"))
    }

    @Test
    fun `two per cent signs write one`() {
        assertEquals("""<div>50% off x</div>""", render("""<div>50%% off %s</div>""", "x"))
    }

    /** A value is data. Nothing in it is read back as markup, including its per cent signs. */
    @Test
    fun `a per cent sign in a value survives untouched`() {
        assertEquals("<li>100%% sure</li>", render("<li>%s</li>", "100%% sure"))
    }

    @Test
    fun `markup with no holes is returned as it stands`() {
        assertEquals("<li>x</li>", render("<li>x</li>"))
        assertEquals("", render(""))
    }

    @Test
    fun `null is written as nothing`() {
        assertEquals("<li></li>", render("<li>%s</li>", null))
    }

    @Test
    fun `a comment is not a tag, so a value in one is text`() {
        assertEquals("<!-- &lt;b&gt; --><li>x</li>", render("<!-- %s --><li>x</li>", "<b>"))
    }

    // Escaping keeps a value inside the attribute. It does nothing about what the browser
    // then does with an attribute it runs.
    @Test
    fun `a value in an event handler or a srcdoc is refused`() {
        for (markup in listOf("""<a onclick="%s">x</a>""", """<a ONCLICK="%s">x</a>""", """<iframe srcdoc="%s"></iframe>""")) {
            val e = assertFailsWith<UnsafeInterpolationException>(markup) { render(markup, "alert(1)") }
            assertEquals(Position.CODE, e.position)
        }
    }

    @Test
    fun `a url attribute takes a relative url or a scheme that only navigates`() {
        assertEquals("""<a href="/heads/1">x</a>""", render("""<a href="%s">x</a>""", "/heads/1"))
        assertEquals(
            """<a href="https://thurn.example/a?b=1&amp;c=2">x</a>""",
            render("""<a href="%s">x</a>""", "https://thurn.example/a?b=1&c=2"),
        )
        assertEquals(
            """<a href="mailto:gorvek@bonereach.example">x</a>""",
            render("""<a href="%s">x</a>""", "mailto:gorvek@bonereach.example"),
        )
        assertEquals("""<a href="">x</a>""", render("""<a href="%s">x</a>""", null))
    }

    @Test
    fun `a url attribute refuses a scheme the browser runs, however it is disguised`() {
        for (url in listOf(
            "javascript:alert(1)",
            "JaVaScRiPt:alert(1)",
            " javascript:alert(1)",
            "java\tscript:alert(1)",
            "\u0001javascript:alert(1)",
            "data:text/html,x",
            "vbscript:x",
        )) {
            val e = assertFailsWith<UnsafeInterpolationException>(url) { render("""<a href="%s">x</a>""", url) }
            assertEquals(Position.URL, e.position)
            assertEquals("href", e.attribute)
        }
        assertFailsWith<UnsafeInterpolationException> { render("""<img src=" %s">""", "javascript:alert(1)") }
        assertFailsWith<UnsafeInterpolationException> { render("""<form action='%s'></form>""", "javascript:alert(1)") }
    }

    // The markup already chose where the URL points; the value only fills in a part of it.
    @Test
    fun `a hole further into a url is an ordinary attribute`() {
        assertEquals("""<a href="/heads/javascript:1">x</a>""", render("""<a href="/heads/%s">x</a>""", "javascript:1"))
    }

    @Test
    fun `a style attribute takes a number and nothing else`() {
        assertEquals("""<i style="width: 40%">x</i>""", render("""<i style="width: %s%%">x</i>""", 40))
        val e = assertFailsWith<UnsafeInterpolationException> { render("""<i style="%s">x</i>""", "background:url(//x)") }
        assertEquals(Position.STYLE, e.position)
    }

    @Test
    fun `a value in an end tag is refused like one in a start tag`() {
        val e = assertFailsWith<UnsafeInterpolationException> { render("<p>x</%s>", "div") }
        assertEquals(Position.STRUCTURE, e.position)
    }

    @Test
    fun `markup interpolate built goes into element text as markup`() {
        val row = interpolate("<li>%s</li>", "<b>Gorvek</b>")

        assertEquals("<ul><li>&lt;b&gt;Gorvek&lt;/b&gt;</li></ul>", render("<ul>%s</ul>", row))
    }

    @Test
    fun `markup interpolate built is only characters anywhere else`() {
        val row = interpolate("<li>%s</li>", "x")

        assertEquals("<p title=\"&lt;li&gt;x&lt;/li&gt;\">y</p>", render("<p title=\"%s\">y</p>", row))
        val e = assertFailsWith<UnsafeInterpolationException> { render("<p data-text=\"%s\"></p>", row) }
        assertEquals(Position.DATASTAR, e.position)
    }

    @Test
    fun `markup interpolate built that leaves a script open is only characters`() {
        val out = render("%s%s</script>", interpolate("<script>"), "alert(document.cookie)")

        assertEquals("&lt;script&gt;alert(document.cookie)</script>", out)
    }

    @Test
    fun `markup interpolate built that leaves an attribute open is only characters`() {
        assertEquals(
            "&lt;a href=&quot;javascript:alert(1)\">x</a>",
            render("%s%s\">x</a>", interpolate("<a href=\""), "javascript:alert(1)"),
        )
        assertEquals(
            "&lt;div data-on:click=&quot;alert(1)\"></div>",
            render("%s%s\"></div>", interpolate("<div data-on:click=\""), "alert(1)"),
        )
    }

    @Test
    fun `markup interpolate built that leaves a comment, a CDATA section or a tag open is only characters`() {
        for (open in listOf("<!-- x", "<svg><![CDATA[x", "<p", "<", "</", "<!x", "<textarea>x", "<svg><title>x")) {
            val out = render("%s%s<script>x</script>", interpolate(open), "y")
            assertTrue(out.startsWith("&lt;"), "$open: $out")
        }
    }

    @Test
    fun `markup interpolate built is only characters in a comment or a raw-text element`() {
        assertEquals(
            "<!-- --&gt; <a href=\"/x\">x</a> -->",
            render("<!-- %s <a href=\"%s\">x</a> -->", interpolate("-->"), "/x"),
        )
        assertEquals("<textarea>&lt;b&gt;x&lt;/b&gt;</textarea>", render("<textarea>%s</textarea>", interpolate("<b>x</b>")))
    }

    @Test
    fun `balanced markup interpolate built still nests`() {
        val inner =
            interpolate(
                "<p title=\"a > b\">%s</p><!-- c --><textarea>t</textarea><script>let x = 1</script><svg><![CDATA[d]]></svg>",
                "<i>",
            )

        assertEquals("<div>${inner.text}</div>", render("<div>%s</div>", inner))
        assertEquals("<ul><li>a</li><li>b</li></ul>", render("<ul>%s%s</ul>", interpolate("<li>a</li>"), interpolate("<li>b</li>")))
    }

    @Test
    fun `an end tag reads its quoted values as a start tag does`() {
        val e =
            assertFailsWith<UnsafeInterpolationException> {
                render("</p x=\"><b title=\"><script>%s</script>\">", "alert(1)")
            }
        assertEquals(Position.CODE, e.position)
    }

    @Test
    fun `a Kotlin template writes the markup of Html and Trusted, not their names`() {
        val row = interpolate("<li>%s</li>", "x")

        assertEquals("<ul><li>x</li></ul>", "<ul>$row</ul>")
        assertEquals("<b>y</b>", "${Trusted("<b>y</b>")}")
    }
}

/** [interpolate]'s markup as text, which is what these tests compare. */
private fun render(
    format: String,
    vararg values: Any?,
): String = interpolate(format, *values).text

private fun render(
    format: String,
    values: List<Any?>,
    prefixes: List<String> = ElementsGuard.defaultPrefixes,
): String = interpolate(format, values, prefixes).text
