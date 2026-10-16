package io.github.markusaugust.streamlord.html

import kotlinx.html.button
import kotlinx.html.div
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** What the DSL writes, held to what Datastar 1.0.4's client and the browser make of it. */
class AsTheClientReadsItTest {
    private fun click(modifiers: OnModifiers.() -> Unit) = elements { button { dataOnClick("x()", modifiers) } }

    // The browser's timers take a negative wait as none and wrap one over 2^31 - 1 ms into a negative one.
    @Test
    fun `a duration the browser's timers cannot honour is refused`() {
        for (wait in listOf((-5).milliseconds, 30.days, Duration.INFINITE)) {
            assertFailsWith<IllegalArgumentException>("$wait") { click { delay = wait } }
            assertFailsWith<IllegalArgumentException>("$wait") { post("/x") { retryInterval = wait } }
        }
        assertEquals(
            """<button data-on:click__delay.0ms__debounce.2147483647ms="x()"></button>""",
            click {
                delay = Duration.ZERO
                debounce = Int.MAX_VALUE.milliseconds
            },
        )
    }

    // Without the duration the flag would be dropped, since the modifier it belongs to is not written.
    @Test
    fun `a flag without its duration is refused`() {
        assertFailsWith<IllegalArgumentException> { click { debounceLeading = true } }
        assertFailsWith<IllegalArgumentException> { click { debounceNoTrailing = true } }
        assertFailsWith<IllegalArgumentException> { click { throttleTrailing = true } }
        assertFailsWith<IllegalArgumentException> { click { throttleNoLeading = true } }
        assertFailsWith<IllegalArgumentException> { elements { div { dataOnInterval("x()") { leading = true } } } }
    }

    @Test
    fun `an interval of zero is refused`() {
        assertFailsWith<IllegalArgumentException> { elements { div { dataOnInterval("x()") { duration = Duration.ZERO } } } }
        assertEquals(
            """<div data-on-interval__duration.1000ms.leading="x()"></div>""",
            elements {
                div {
                    dataOnInterval("x()") {
                        duration = 1.seconds
                        leading = true
                    }
                }
            },
        )
    }

    // onIntersect.ts reads full, then half, then threshold, and drops the rest.
    @Test
    fun `half, full and threshold are alternatives`() {
        assertFailsWith<IllegalArgumentException> {
            elements {
                div {
                    dataOnIntersect("x()") {
                        half = true
                        full = true
                    }
                }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            elements {
                div {
                    dataOnIntersect("x()") {
                        full = true
                        threshold = 25
                    }
                }
            }
        }
    }

    @Test
    fun `retry options that turn the backoff into no wait are refused`() {
        assertFailsWith<IllegalArgumentException> { post("/x") { retryMaxCount = -1 } }
        assertFailsWith<IllegalArgumentException> { post("/x") { retryScaler = -1.0 } }
        assertEquals(
            "@post('/x', {retryScaler: 0.0, retryMaxCount: 0})",
            post("/x") {
                retryScaler = 0.0
                retryMaxCount = 0
            },
        )
    }

    // Each was checked against Node: the literal compiles and matches what the Kotlin Regex matches.
    @Test
    fun `a regex reaches JavaScript meaning what it meant in Kotlin`() {
        assertEquals("""/^cart\.items/""", SignalFilter.regexLiteral(Regex("^" + Regex.escape("cart.items"))))
        assertEquals("""/x\\Ey/""", SignalFilter.regexLiteral(Regex(Regex.escape("x\\Ey"))))
        assertEquals("""/[\]\-\^]/""", SignalFilter.regexLiteral(Regex("[\\Q]-^\\E]")))
        assertEquals("/a\\u2028b\\u2029c/", SignalFilter.regexLiteral(Regex("a b c")))
        assertEquals("""/x}+/""", SignalFilter.regexLiteral(Regex("x}+")))
        assertEquals("/(?:)/", SignalFilter.regexLiteral(Regex("\\Q\\E")))
        for (java in listOf(
            "a++",
            "a{2}+",
            "(?>a)",
            "\\Aa",
            "a\\z",
            "a\\Z",
            "\\Gx",
            "\\p{L}",
            "\\x{41}",
            "\\h",
            "\\R",
            "\\e",
            "[a[b]]",
            "[a&&b]",
            "[]a]",
        )) {
            assertFailsWith<IllegalArgumentException>(java) { SignalFilter.regexLiteral(Regex(java)) }
        }
    }

    // engine.ts reads \w+ after a `$`, so these names would reach no signal.
    @Test
    fun `a signal name no reference reaches is refused`() {
        for (name in listOf("über", "a:b", ".a", "a.", "a`b", "a--b")) {
            assertFailsWith<InvalidSignalNameException>(name) { elements { div { dataSignals(name, "1") } } }
            assertFailsWith<InvalidSignalNameException>(name) { elements { div { dataBind(name) } } }
            assertFailsWith<InvalidSignalNameException>(name) { elements { div { dataSignals(name to 1) } } }
        }
        assertFailsWith<InvalidSignalNameException> { signal("über") }
        assertFailsWith<InvalidSignalNameException> { signal("\$count") }
        assertEquals("\$['foo-bar']", signal("['foo-bar']"))
        assertEquals("\$_private1", signal("_private1"))
    }

    // A / ends the attribute name in the HTML tokenizer; it reached kotlinx.html as its own exception.
    @Test
    fun `a slash in a key is refused`() {
        assertFailsWith<InvalidSignalNameException> { elements { div { dataClass("w-1/2", "true") } } }
    }

    // data-signals:foo-bar declares $fooBar, and signal("foo-bar") is $fooBar, so the pairs form follows.
    @Test
    fun `the pairs form names the signal the keyed form and signal() name`() {
        assertEquals(
            """<div data-signals="{&quot;fooBar&quot;:7,&quot;user&quot;:{&quot;firstName&quot;:&quot;&quot;},""" +
                """&quot;rows&quot;:[{&quot;subTotal&quot;:1}]}"></div>""",
            elements {
                div { dataSignals("foo-bar" to 7, "user" to mapOf("first-name" to ""), "rows" to listOf(mapOf("sub-total" to 1))) }
            },
        )
        assertEquals("\$user.firstName", signal("user.first-name"))
        assertFailsWith<InvalidSignalNameException> { elements { div { dataSignals("foo-bar" to 1, "fooBar" to 2) } } }
        assertFailsWith<InvalidSignalNameException> { elements { div { dataSignals("a" to mapOf("x-y" to 1, "xY" to 2)) } } }
        assertFailsWith<IllegalArgumentException> { elements { div { dataSignals("a" to mapOf("__proto__" to 1)) } } }
    }

    // CSS custom properties are case-sensitive and the browser lowercases the key.
    @Test
    fun `a custom property with capitals cannot travel in a key`() {
        assertFailsWith<InvalidSignalNameException> { elements { div { dataStyle("--brandColor", "'red'") } } }
        assertEquals("""<div data-style:--brand="'red'"></div>""", elements { div { dataStyle("--brand", "'red'") } })
    }
}
