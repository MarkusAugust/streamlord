package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.application.Streamlord
import io.github.markusaugust.streamlord.core.domain.ElementsGuard
import io.github.markusaugust.streamlord.core.domain.ElementsResponse
import io.github.markusaugust.streamlord.core.domain.InterpolatedExpressionException
import io.github.markusaugust.streamlord.core.domain.MistypedAttributeException
import io.github.markusaugust.streamlord.core.domain.PatchElements
import io.github.markusaugust.streamlord.core.port.driven.BufferedSseSink
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ElementsGuardTest {
    /** Written with `$$`, so every `$` below is a dollar the browser will see. */
    private val sound =
        $$"""
        <!-- data-text="$eaten" in a comment is not an attribute -->
        <div id="a" data-signals="{count: 0, open: false}" data-signals:name="'Gorvek'">
          <button data-on:click__debounce.500ms="$count++" data-on-intersect="@get('/more')">Raise</button>
          <span data-text="$count" data-show="!$open" data-class:active="$open" data-attr:title="$name"></span>
          <input data-bind:draft data-indicator="busy" data-ref:field data-preserve-attr="style" data-size="xs">
          <p data-star-text='$count'>aliased prefix, single quotes</p>
          <a href="/x?a=1&b=2" data-on:click="evt.preventDefault(); $open = !$open">x</a>
          <p data-computed:double="$count * 2" data-effect="console.log($count)"></p>
          <div data-show="$count > 1 && $count < 10"></div>
          <input data-on:keydown="evt.key === 'Escape' && ($open = false)" data-custom-validity="$ok ? '' : 'no'">
        </div>
        """.trimIndent()

    @Test
    fun `working markup passes untouched`() {
        assertEquals(sound, ElementsGuard.check(sound))
        assertEquals("", ElementsGuard.check(""))
        assertEquals("no tags at all", ElementsGuard.check("no tags at all"))
        assertEquals("<br><img src=x><div/>", ElementsGuard.check("<br><img src=x><div/>"))
    }

    /** What a Kotlin template leaves behind, as the attribute the browser would receive. */
    @Test
    fun `the traces of an eaten signal are refused with the attribute named`() {
        val broken =
            mapOf(
                """<div data-text=""></div>""" to "data-text",
                """<button data-on:click="++"></button>""" to "data-on:click",
                """<div data-show=" = !"></div>""" to "data-show",
                """<div data-class:active=""></div>""" to "data-class:active",
                """<div data-computed:double=" * 2"></div>""" to "data-computed:double",
                """<div data-star-text=''></div>""" to "data-star-text",
                """<div data-on:click__debounce.500ms=""></div>""" to "data-on:click__debounce.500ms",
                """<span data-text></span>""" to "data-text",
                """<p>fine</p><div id="x" data-on-intersect="@get('/x') && "></div>""" to "data-on-intersect",
            )
        for ((html, attribute) in broken) {
            val e = assertFailsWith<InterpolatedExpressionException>("should refuse $html") { ElementsGuard.check(html) }
            assertEquals(attribute, e.attribute, html)
            assertTrue(e.message!!.contains("$attribute=\""), e.message)
            assertTrue(e.message!!.contains("\$\$\"\"\""), e.message)
        }
    }

    @Test
    fun `a name one letter from a datastar attribute is a typo, further away is yours`() {
        val typos =
            mapOf(
                """<div data-onn:click="x()"></div>""" to ("data-onn:click" to "data-on:click"),
                """<div data-signal:foo="1"></div>""" to ("data-signal:foo" to "data-signals:foo"),
                """<div data-txt:x="1"></div>""" to ("data-txt:x" to "data-text:x"),
                """<div data-indicater="busy"></div>""" to ("data-indicater" to "data-indicator"),
                """<div data-signal:a="1"></div>""" to ("data-signal:a" to "data-signals:a"),
                """<div data-computer="x"></div>""" to ("data-computer" to "data-computed"),
                """<div data-signal="{a: 1}" data-animated data-ignored data-effects="fade" data-styles="x"></div>""" to null,
                """<div data-txt="1"></div>""" to null,
                """<div data-test="submit" data-kind="x" data-once data-none data-styles="a"></div>""" to null,
                """<div data-unit="kg" data-attrs="b" data-red data-of="x"></div>""" to null,
                """<div data-on-intersec__once="x()"></div>""" to ("data-on-intersec__once" to "data-on-intersect__once"),
                """<div data-star-signal="1"></div>""" to null,
                """<div data-star-indicater="1"></div>""" to ("data-star-indicater" to "data-star-indicator"),
                """<div data-star-shw:x="1"></div>""" to ("data-star-shw:x" to "data-star-show:x"),
                """<div data-star-shw="1"></div>""" to null,
                """<div data-Bind="x"></div>""" to null,
            )
        for ((html, expected) in typos) {
            if (expected == null) {
                assertEquals(html, ElementsGuard.check(html))
                continue
            }
            val e = assertFailsWith<MistypedAttributeException>("should refuse $html") { ElementsGuard.check(html) }
            assertEquals(expected.first, e.attribute)
            assertEquals(expected.second, e.suggestion)
            assertTrue(e.message!!.contains("Did you mean"), e.message)
        }
        // Your own data-* attributes, as a design system uses them, pass untouched.
        val own =
            """<div data-size="xs" data-color="success" data-theme="dark" data-state="invalid" data-variant="plain" """ +
                """data-picker="styled" data-frist="1" data-nede="false" data-klokke data-alle="0" data-server-na="1" """ +
                """data-on-load="x()" data-ref-id="7"></div>"""
        assertEquals(own, ElementsGuard.check(own))
    }

    @Test
    fun `entities are decoded and a custom prefix can be added`() {
        // kotlinx.html and template engines escape attribute values; the browser decodes them before Datastar reads.
        assertFailsWith<InterpolatedExpressionException> { ElementsGuard.check("""<div data-show=" &amp;&amp; ${'$'}b"></div>""") }
        assertFailsWith<InterpolatedExpressionException> { ElementsGuard.check("""<div data-show=" &lt; 10"></div>""") }
        val fine = """<div data-show="${'$'}a &amp;&amp; ${'$'}b &lt; 10" data-text="&quot;x&quot; + ${'$'}y"></div>"""
        assertEquals(fine, ElementsGuard.check(fine))
        // Decoded once, as the browser does: &amp;lt; is the text &lt;, not an operator.
        val doubly = """<div data-show="&amp;lt; 10" data-text="'&amp;quot;'"></div>"""
        assertEquals(doubly, ElementsGuard.check(doubly))
        assertEquals("""<div data-ds-text=""></div>""", ElementsGuard.check("""<div data-ds-text=""></div>"""))
        val aliased = listOf("data-ds-") + ElementsGuard.defaultPrefixes
        assertFailsWith<InterpolatedExpressionException> { ElementsGuard.check("""<div data-ds-text=""></div>""", aliased) }
        val guarded = Streamlord(guardElements = true, attributePrefixes = aliased)
        assertFailsWith<InterpolatedExpressionException> { guarded.guard(PatchElements("""<div data-ds-text=""></div>""")) }
        Streamlord(guardElements = true).guard(PatchElements("""<div data-ds-text=""></div>"""))
    }

    @Test
    fun `script and style bodies are text, not tags`() {
        val html =
            """<div id="a"><script>el.innerHTML = '<span data-text=""></span>'; if (a<b) run();</script>""" +
                """<style>a<b { color: red }</style><STYLE>x</STYLE><p data-text=""></p></div>"""
        val e = assertFailsWith<InterpolatedExpressionException> { ElementsGuard.check(html) }
        assertEquals("data-text", e.attribute)
        val fine = html.replace("""<p data-text=""></p>""", "")
        assertEquals(fine, ElementsGuard.check(fine))
        assertEquals("""<scripter data-text="x"></scripter>""", ElementsGuard.check("""<scripter data-text="x"></scripter>"""))
    }

    @Test
    fun `attributes that take no expression are left alone`() {
        for (ok in listOf(
            """<input data-bind="">""",
            """<div data-indicator=""></div>""",
            """<div data-ref:x data-ignore data-json-signals="" data-persist=""></div>""",
            """<div data-size="" data-theme="" data-foo=""></div>""",
        )) {
            assertEquals(ok, ElementsGuard.check(ok))
        }
    }

    @Test
    fun `a closing angle bracket inside a value does not end the tag`() {
        val e =
            assertFailsWith<InterpolatedExpressionException> { ElementsGuard.check("""<div data-show="${'$'}a > 1" data-text=""></div>""") }
        assertEquals("data-text", e.attribute)
    }

    @Test
    fun `the guard is off by default and on by choice`() =
        runTest {
            val broken = """<div id="a" data-text=""></div>"""
            val silent = BufferedSseSink()
            Streamlord.Default.stream(silent).patchElements(broken)
            assertTrue(silent.toString().contains("data-text=\"\""))

            val guarded = Streamlord(guardElements = true)
            assertFailsWith<InterpolatedExpressionException> { guarded.stream(BufferedSseSink()).patchElements(broken) }
            assertFailsWith<InterpolatedExpressionException> { guarded.encode(flowOf(PatchElements(broken))).toList() }
            assertFailsWith<InterpolatedExpressionException> { guarded.guard(ElementsResponse(broken)) }
            assertFailsWith<InterpolatedExpressionException> { guarded.guard(PatchElements(broken)) }

            val fine = """<div id="a" data-text="${'$'}count"></div>"""
            val sink = BufferedSseSink()
            guarded.stream(sink).patchElements(fine)
            assertTrue(sink.toString().contains("data-text=\"\$count\""))
            assertEquals(1, guarded.encode(flowOf(PatchElements(fine))).toList().size)
            guarded.guard(PatchElements.remove("#gone"))
        }
}
