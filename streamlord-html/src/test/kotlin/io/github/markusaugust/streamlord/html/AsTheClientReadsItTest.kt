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
        assertEquals("""/x\x5cEy/""", SignalFilter.regexLiteral(Regex(Regex.escape("x\\Ey"))))
        assertEquals("""/[\]\-\^]/""", SignalFilter.regexLiteral(Regex("[\\Q]-^\\E]")))
        assertEquals("/a\\u2028b\\u2029c/", SignalFilter.regexLiteral(Regex("a b c")))
        assertEquals("""/x}+/""", SignalFilter.regexLiteral(Regex("x}+")))
        assertEquals("/(?:)/", SignalFilter.regexLiteral(Regex("\\Q\\E")))
        assertEquals("""/\(\?i\)/""", SignalFilter.regexLiteral(Regex(Regex.escape("(?i)"))))
        assertEquals("""/\x41\xff/""", SignalFilter.regexLiteral(Regex("\\0101\\0377")))
        assertEquals("""/(a)\1/""", SignalFilter.regexLiteral(Regex("(a)\\1")))
        assertEquals("/^(?:😀)+$/", SignalFilter.regexLiteral(Regex("^😀+$")))
        val javaOnly =
            listOf(
                "a++",
                "a{2}+",
                "(?>a)",
                "(?i)a",
                "\\Aa",
                "a\\z",
                "a\\Z",
                "\\Gx",
                "\\p{L}",
                "\\x{41}",
                "\\h",
                "\\R",
                "\\e",
                "\\ca",
                "\\c[",
                "(a)\\11",
                "a\\1",
                "x{1}{2}",
                "a*{2}",
                "a\\b{g}",
                "[😀]",
                "\\uD83D\\uDE00",
                "[a[b]]",
                "[a&&b]",
                "[]a]",
            )
        for (java in javaOnly) {
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
        assertFailsWith<InvalidSignalNameException> { elements { div { dataSignals("a" to mapOf("__proto__" to 1)) } } }
    }

    // data-signals:a.b nests, and the pairs form now does too; a dotted key inside a map the client
    // would store as one key, which $a.b does not reach and a request drops.
    @Test
    fun `a dotted pair nests as the keyed form does`() {
        assertEquals(
            """<div data-signals="{&quot;form&quot;:{&quot;email&quot;:&quot;&quot;,&quot;firstName&quot;:&quot;&quot;},""" +
                """&quot;n&quot;:1}"></div>""",
            elements { div { dataSignals("form.email" to "", "form.first-name" to "", "n" to 1) } },
        )
        assertEquals(
            """<div data-signals="{&quot;a&quot;:{&quot;b&quot;:1,&quot;c&quot;:2}}"></div>""",
            elements { div { dataSignals("a" to mapOf("b" to 1), "a.c" to 2) } },
        )
        assertFailsWith<InvalidSignalNameException> { elements { div { dataSignals("a" to 1, "a.b" to 2) } } }
        assertFailsWith<InvalidSignalNameException> { elements { div { dataSignals("a" to mapOf("b.c" to 1)) } } }
    }

    // Measured against the 1.0.4 store: __proto__ throws midway through a batch, and constructor
    // with an object under it reaches Object.prototype.
    @Test
    fun `a name the client cannot hold is refused`() {
        for (name in listOf("constructor", "constructor.prototype.x", "a.constructor", "__proto__.x")) {
            assertFailsWith<InvalidSignalNameException>(name) { elements { div { dataSignals(name, "1") } } }
            assertFailsWith<InvalidSignalNameException>(name) { elements { div { dataSignals(name to 1) } } }
            assertFailsWith<InvalidSignalNameException>(name) { elements { div { dataBind(name) } } }
            assertFailsWith<InvalidSignalNameException>(name) { elements { div { dataComputed(name, "1") } } }
        }
        val prototypeUnder = "a" to mapOf("constructor" to mapOf("prototype" to 1))
        assertFailsWith<InvalidSignalNameException> { elements { div { dataSignals(prototypeUnder) } } }
        assertEquals("""<div data-signals:constructors="1"></div>""", elements { div { dataSignals("constructors", "1") } })
    }

    // CSS custom properties are case-sensitive and the browser lowercases the key.
    @Test
    fun `a custom property with capitals cannot travel in a key`() {
        assertFailsWith<InvalidSignalNameException> { elements { div { dataStyle("--brandColor", "'red'") } } }
        assertEquals("""<div data-style:--brand="'red'"></div>""", elements { div { dataStyle("--brand", "'red'") } })
    }

    // engine.ts:415-416: how the client cuts the expression of an attribute whose value it reads,
    // such as data-text or data-signals, into statements. It reads a quote or `/` after a `\` as
    // escaped, so a string or regex that ends in `\` would run on, and a `;` after it would cut
    // the expression in the middle of a string.
    private val statements =
        Regex(
            """(?:\/(?:\\\/|[^/])*\/|"(?:\\"|[^"])*"|'(?:\\'|[^'])*'|`(?:\\`|[^`])*`|""" +
                """\(\s*(?:(?:function)\s*\(\s*\)|(?:\(\s*\))\s*=>)\s*(?:\{[\s\S]*?\}|[^;){]*)\s*\)\s*\(\s*\)|[^;])+""",
        )

    // engine.ts:448: how it rewrites `$name` as a signal and `@name(` as an action, in every
    // attribute. It skips strings but knows no regex literals, so a quote in one hides the signals
    // after it.
    private val rewrites =
        Regex(
            """(?:"[^"\\]*(?:\\.[^"\\]*)*"|'[^'\\]*(?:\\.[^'\\]*)*'|`[^`\\$]*(?:(?:\\.|\$(?!\{))[^`\\$]*)*`)|""" +
                """\$\{([^{}]*)\}|\$(\w+(?:[.-]\w+)*)|@([A-Za-z_$][\w$]*)\(""",
        )

    private fun statementsIn(expression: String) = statements.findAll(expression).map { it.value.trim() }.toList()

    private fun rewritten(
        expression: String,
        group: Int,
    ) = rewrites.findAll(expression).mapNotNull { it.groups[group]?.value }.toList()

    private fun signalsIn(expression: String) = rewritten(expression, 2)

    private fun actionsIn(expression: String) = rewritten(expression, 3)

    private fun signalsAttribute(html: String) =
        Regex("""data-signals="([^"]*)"""")
            .find(html)!!
            .groupValues[1]
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")

    @Test
    fun `a value ending in a backslash stays inside its string`() {
        for (value in listOf("C:\\", "a\\'", "\\\"", "x\\\\")) {
            assertEquals(1, statementsIn(js(value) + " + " + js(";")).size, value)
            val pairs = signalsAttribute(elements { div { dataSignals("p1" to value, "p2" to ";") } })
            assertEquals(1, statementsIn(pairs).size, value)
        }
    }

    @Test
    fun `a regex filter hides no signal or action after it`() {
        val patterns =
            listOf(
                "x\\\\",
                "\\'",
                "it's",
                "a\"b",
                "back`tick",
                "^[_\$a-z]+\$",
                "^a\$b",
                "\\\$x",
                "a@b(c)?",
                "\$\\Qab\\E?",
                "a\$\\Q_\\E",
            )
        for (pattern in patterns) {
            val literal = SignalFilter.regexLiteral(Regex(pattern))
            val expression = setAll(true, SignalFilter(include = Regex(pattern))) + "; " + set("msg", "done")
            assertEquals(listOf("msg"), signalsIn(expression), pattern)
            assertEquals(listOf("setAll"), actionsIn(expression), pattern)
            // In data-text, a `/` later on would close a literal whose last `\` escaped its own end.
            assertEquals(1, statementsIn("$literal.source + '/;'").size, pattern)
        }
        assertEquals("/^[_\\x24a-z]+\$/", SignalFilter.regexLiteral(Regex("^[_\$a-z]+\$")))
        assertEquals("/^a\$(?:)b/", SignalFilter.regexLiteral(Regex("^a\$b")))
        assertEquals("/\$(?:)ab?/", SignalFilter.regexLiteral(Regex("\$\\Qab\\E?")))
        assertEquals("/\\x24x a\\x40b/", SignalFilter.regexLiteral(Regex("\\\$x a@b")))
    }

    @Test
    fun `an object key holding a dollar is quoted`() {
        assertEquals("{'\$a': 1, b_1: 2}", JsLiteral.write(mapOf("\$a" to 1, "b_1" to 2)))
        assertEquals(listOf("o"), signalsIn(set("o", mapOf("\$a" to 1, "_\$x" to 2))))
    }

    // The single quotes around a literal are the only ones it holds, so it can stand between the
    // double quotes of an attribute written in a string or a template, and no entity is decoded.
    @Test
    fun `a helper's literal holds no quote or ampersand of its own`() {
        val literal = set("v", "\"><img src=x onerror=alert(1)> & 'it' `x`")
        assertEquals("\$v = '\\x22><img src=x onerror=alert(1)> \\x26 \\x27it\\x27 `x`'", literal)
        val regex = SignalFilter.regexLiteral(Regex("it's \"a\" `b` & c\\\\"))
        assertEquals("/it\\x27s \\x22a\\x22 \\x60b\\x60 \\x26 c\\x5c/", regex)
    }
}
