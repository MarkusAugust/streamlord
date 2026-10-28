package io.github.markusaugust.streamlord.html

import io.github.markusaugust.streamlord.core.SignalsCodecException
import io.github.markusaugust.streamlord.core.domain.ExpressionGuard
import io.github.markusaugust.streamlord.core.domain.InterpolatedExpressionException
import io.github.markusaugust.streamlord.core.json.JsonParser
import kotlinx.html.DIV
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.input
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.microseconds
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

    // A pair's key is the signal's name, so it holds no hyphen; the client merges the value as it
    // is, so the keys inside it keep theirs.
    @Test
    fun `the pairs form names the signal the keyed form and signal() name`() {
        assertEquals(
            """<div data-signals="{&quot;fooBar&quot;:7,&quot;user&quot;:{&quot;first-name&quot;:&quot;&quot;},""" +
                """&quot;rows&quot;:[{&quot;sub-total&quot;:1}]}"></div>""",
            elements {
                div { dataSignals("fooBar" to 7, "user" to mapOf("first-name" to ""), "rows" to listOf(mapOf("sub-total" to 1))) }
            },
        )
        assertEquals("\$user['first-name']", signal("user['first-name']"))
        assertEquals(
            """<div data-signals="{&quot;a&quot;:{&quot;x-y&quot;:1,&quot;xY&quot;:2}}"></div>""",
            elements { div { dataSignals("a" to mapOf("x-y" to 1, "xY" to 2)) } },
        )
        assertFailsWith<InvalidSignalNameException> { elements { div { dataSignals("foo-bar" to 1) } } }
        assertFailsWith<InvalidSignalNameException> { elements { div { dataSignals("fooBar" to 1, "fooBar" to 2) } } }
        assertFailsWith<InvalidSignalNameException> { elements { div { dataSignals("a" to mapOf("__proto__" to 1)) } } }
    }

    // The client merges the object as it is, so a codec's names reach the page as the handler reads them.
    @Test
    fun `a JSON object declares its keys as written`() {
        val json = JsonParser.parse("""{"email":"","first-name":"","big":12345678901234567890,"tags":[0.10000000000000000555]}""")
        assertEquals(
            """<div data-signals="{&quot;email&quot;:&quot;&quot;,&quot;first-name&quot;:&quot;&quot;,""" +
                """&quot;big&quot;:12345678901234567890,&quot;tags&quot;:[0.10000000000000000555]}"></div>""",
            elements { div { dataSignals(json) } },
        )
        assertEquals(
            "\$o = {big: 12345678901234567890, tags: [0.10000000000000000555]}",
            set("o", JsonParser.parse("""{"big":12345678901234567890,"tags":[0.10000000000000000555]}""")),
        )
        assertFailsWith<IllegalArgumentException> { elements { div { dataSignals(JsonParser.parse("[1]")) } } }
        for (key in listOf("a.b", "", "toString", "constructor")) {
            assertFailsWith<InvalidSignalNameException>(key) { elements { div { dataSignals(JsonParser.parse("""{"$key":1}""")) } } }
        }
        assertFailsWith<InvalidSignalNameException> { elements { div { dataSignals(JsonParser.parse("""{"a":{"__proto__":1}}""")) } } }
    }

    // data-signals:a.b nests, and the pairs form now does too; a dotted key inside a map the client
    // would store as one key, which $a.b does not reach and a request drops.
    @Test
    fun `a dotted pair nests as the keyed form does`() {
        assertEquals(
            """<div data-signals="{&quot;form&quot;:{&quot;email&quot;:&quot;&quot;,&quot;firstName&quot;:&quot;&quot;},""" +
                """&quot;n&quot;:1}"></div>""",
            elements { div { dataSignals("form.email" to "", "form.firstName" to "", "n" to 1) } },
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
        val names = listOf("constructor", "constructor.prototype.x", "a.constructor", "__proto__.x", "toString.name", "to-string.name")
        for (name in names) {
            assertFailsWith<InvalidSignalNameException>(name) { elements { div { dataSignals(name, "1") } } }
            assertFailsWith<InvalidSignalNameException>(name) { elements { div { dataSignals(name to 1) } } }
            assertFailsWith<InvalidSignalNameException>(name) { elements { div { dataBind(name) } } }
            assertFailsWith<InvalidSignalNameException>(name) { elements { div { dataRef(name) } } }
            assertFailsWith<InvalidSignalNameException>(name) { elements { div { dataIndicator(name) } } }
            assertFailsWith<InvalidSignalNameException>(name) { elements { div { dataComputed(name, "1") } } }
        }
        val prototypeUnder = "a" to mapOf("constructor" to mapOf("prototype" to 1))
        assertFailsWith<InvalidSignalNameException> { elements { div { dataSignals(prototypeUnder) } } }
        // A key inside a value is not camel-cased, so to-string is not toString.
        assertEquals(
            """<div data-signals="{&quot;a&quot;:{&quot;to-string&quot;:{&quot;name&quot;:1}}}"></div>""",
            elements { div { dataSignals("a" to mapOf("to-string" to mapOf("name" to 1))) } },
        )
        // With a plain value under it, the client holds such a key.
        assertEquals(
            """<div data-signals="{&quot;team&quot;:{&quot;constructor&quot;:&quot;Ferrari&quot;}}"></div>""",
            elements { div { dataSignals("team" to mapOf("constructor" to "Ferrari")) } },
        )
        assertEquals("""<div data-signals:constructors="1"></div>""", elements { div { dataSignals("constructors", "1") } })
        val clash = assertFailsWith<InvalidSignalNameException> { elements { div { dataSignals("n" to 1, "a" to 1, "a.b" to 2) } } }
        assertEquals("a", clash.name)
    }

    // The browser lowercases the key, and the client cuts it at the first `__`, so the name it
    // holds is not always the name written.
    @Test
    fun `a name is checked as the client reads it from the key`() {
        val refused =
            listOf(
                { d: DIV -> d.dataSignals("constructor_", "{prototype: {w: 1}}", ifMissing = true) },
                { d: DIV -> d.dataBind("constructor.prototype.bound") },
            )
        for ((i, write) in refused.withIndex()) {
            assertFailsWith<InvalidSignalNameException>("case $i") { elements { div { write(this) } } }
        }
        // Without a modifier the client keeps the trailing `_`.
        assertEquals("""<div data-signals:constructor_="1"></div>""", elements { div { dataSignals("constructor_", "1") } })
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

    // Run through modifyCasing from the 1.0.4 source: no key over {a, b, -} up to nine
    // characters, with any chain of up to three __case values, gives a name of the shape aB-b.
    @Test
    fun `an event or class name mixing capitals with a hyphen before a lower-case letter is refused`() {
        for (name in listOf("fooBar-baz", "foo-barBaz", "Foo-bar", "--brandColor")) {
            assertFailsWith<InvalidSignalNameException>(name) { elements { div { dataClass(name, "true") } } }
            assertFailsWith<InvalidSignalNameException>(name) { elements { div { dataOn(name, "x()") } } }
        }
        assertEquals(
            """<div data-class:foo--bar__case.camel="true" data-on:item-1__case.pascal="x()" data-class:foo-bar="true"></div>""",
            elements {
                div {
                    dataClass("foo-Bar", "true")
                    dataOn("Item-1", "x()")
                    dataClass("foo-bar", "true")
                }
            },
        )
        // A signal name holds no hyphen at all.
        assertFailsWith<InvalidSignalNameException> { elements { div { dataSignals("fooBar-baz", "1") } } }
    }

    // bind.ts listens to each __event value as the browser hands it over, lowercased, after
    // parseAttributeKey has split the modifier at each `.`.
    @Test
    fun `a bind event the client would not listen to is refused`() {
        for (event in listOf("myChange", "my.change", "a__b", "", "a b")) {
            assertFailsWith<InvalidSignalNameException>(event) { elements { input { dataBind("v") { events = listOf(event) } } } }
        }
        assertEquals(
            """<input data-bind__event.my-change.input="v">""",
            elements { input { dataBind("v") { events = listOf("my-change", "input") } } },
        )
    }

    // bind.ts reads __prop with its own camel(), whose kebab splits letters from digits and turns `_` into a hyphen.
    @Test
    fun `a bind property comes back from the client as written or is refused`() {
        for (prop in listOf("x2y", "_private", "my_prop", "a.b")) {
            assertFailsWith<InvalidSignalNameException>(prop) { elements { input { dataBind("v") { this.prop = prop } } } }
        }
        assertEquals(
            """<input data-bind__prop.-u-r-l="v" data-bind__prop.inner-h-t-m-l="w" data-bind__prop.x2-y="z">""",
            elements {
                input {
                    dataBind("v") { prop = "URL" }
                    dataBind("w") { prop = "innerHTML" }
                    dataBind("z") { prop = "x2Y" }
                }
            },
        )
    }

    // class.ts splits a class list on JavaScript's \s, which has the no-break space; the HTML
    // parser turns NUL into U+FFFD.
    @Test
    fun `a key with a no-break space or a NUL is refused`() {
        for (name in listOf("a\u00a0b", "a\u0000b", "a\u2028b", "a\ufeffb", "a\u3000b")) {
            assertFailsWith<InvalidSignalNameException>(name) { elements { div { dataClass(name, "true") } } }
        }
    }

    // parseAttributeKey splits a modifier at each `.`, so `__delay.1.5ms` reaches tagToMs as `1` and
    // `5ms`, and it takes the first: 1 ms. Headless Chrome on the 1.0.4 bundle ran
    // `data-on:click__delay.1.900ms` within 100 ms of the click. The DSL writes the same 1 ms.
    @Test
    fun `a fraction of a millisecond is rounded down, as the client reads it`() {
        assertEquals("""<button data-on:click__debounce.1ms="x()"></button>""", click { debounce = 1.5.milliseconds })
        assertEquals("""<button data-on:click__delay.1900ms="x()"></button>""", click { delay = 1900.9.milliseconds })
        val options =
            post("/x") {
                retryInterval = 1500.microseconds
                retryMaxWait = 2999.microseconds
            }
        assertEquals("@post('/x', {retryInterval: 1, retryMaxWait: 2})", options)
        assertFailsWith<IllegalArgumentException> { click { debounce = 999.microseconds } }
    }

    // What `$a - $b`, `$a + $b`, `$a - $y - $z` and `$a - $b * 2` leave once Kotlin has eaten
    // `$a`, and what `[ $a, 1]` leaves. A sign against its operand still passes.
    @Test
    fun `what a template leaves of a binary operator or an array is refused`() {
        for (expression in listOf(" - \$b", " + \$b", " - \$y - \$z", " - \$b * 2", "- \$b", "\$x = [ , 1]", "\$x = [, 1]", "[1, , 2]")) {
            assertFailsWith<InterpolatedExpressionException>(expression) {
                elements { div { dataText(expression) } }
            }
        }
        for (expression in listOf("-\$x", "-1", "+\$n", "!\$open", "-\$x * 2", "\$x = [undefined, 1]")) {
            assertEquals(expression, ExpressionGuard.check(expression))
        }
    }

    // Each refused here is refused by Chrome with "Nothing to repeat" or "Invalid group"; the
    // ones written are what it compiles. The fuzz in the audit's harness checks the rest.
    @Test
    fun `a quantifier with nothing to repeat is refused`() {
        val refused =
            listOf(
                "$?",
                "$*",
                "$+",
                "\${1,2}",
                "^{2}",
                "^*",
                "\\b*",
                "\\B{1}",
                "{1}",
                "{1,2}",
                "a|{1}",
                "a|*",
                "(?<!{1}a)",
                "(?<=*a)",
                "(?={1}a)",
                "(?!*a)",
                "({1})",
                "(?:*a)",
                "\\Q\\E{1}",
                "(?<=a)*b",
                "(?<!a){2}b",
                "(?)a",
            )
        for (pattern in refused) {
            assertFailsWith<IllegalArgumentException>(pattern) { SignalFilter.regexLiteral(Regex(pattern)) }
        }
        assertEquals("/(?=a)*a/", SignalFilter.regexLiteral(Regex("(?=a)*a")))
        assertEquals("/a*/", SignalFilter.regexLiteral(Regex("a*\\Q\\E")))
        assertEquals("/a{1}/", SignalFilter.regexLiteral(Regex("a\\Q\\E{1}")))
        assertEquals("/(?<n>a){2}[a]{2}(a)*/", SignalFilter.regexLiteral(Regex("(?<n>a){2}[a]{2}(a)*")))
    }

    // Headless Chrome on the 1.0.4 bundle: `data-class:b___case.pascal` set the class `b`, since
    // the client cuts the name at the first `__`; `data-class:b_` set `b_`.
    @Test
    fun `a key that ends in _ before a modifier is refused`() {
        val refused =
            listOf<DIV.() -> Unit>(
                { dataClass("B_", "true") },
                { dataClass("b_", "true", Case.CAMEL) },
                { dataOn("my-event_", "x()") { debounce = 1.milliseconds } },
                { dataSignals("b_", "1", ifMissing = true) },
                { dataComputed("B_", "1") },
            )
        for (attribute in refused) {
            assertFailsWith<InvalidSignalNameException> { elements { div { attribute(this) } } }
        }
        assertEquals(
            """<div data-class:b_="true" data-on:my-event_="x()" data-signals:b_="1"></div>""",
            elements {
                div {
                    dataClass("b_", "true")
                    dataOn("my-event_", "x()")
                    dataSignals("b_", "1")
                }
            },
        )
    }

    // In headless Chrome `data-class="{'MuiButton-root': true}"` set the class `MuiButton-root`.
    @Test
    fun `a class no key gives back points to the object form`() {
        val e = assertFailsWith<InvalidSignalNameException> { elements { div { dataClass("MuiButton-root", "true") } } }
        assertEquals(
            "Name \"MuiButton-root\" mixes capitals with a hyphen before a lower-case letter; Datastar's client turns that " +
                "hyphen into a capital as well, so it would read MuiButtonRoot. Write it in one case, or, for a class you " +
                "cannot rename, use the object form, dataClass(\"{'MuiButton-root': ...}\"), whose keys Datastar uses as written.",
            e.message,
        )
        assertEquals(
            """<div data-class="{'MuiButton-root': true}"></div>""",
            elements { div { dataClass("{'MuiButton-root': true}") } },
        )
    }

    // JavaScript, measured in headless Chrome, matches a back reference to a group that has not
    // closed as empty: /^(a\1)$/ and /^\1(a)$/ both match "a". Java fails it on the group's first
    // pass, and in a repeat matches what the group took before: ^(a|b\1)+$ takes "aba" in Java and
    // "ab" in JavaScript.
    @Test
    fun `a back reference before its group has closed is refused`() {
        for (pattern in listOf("(a\\1)", "\\1(a)", "(a(b)\\1)", "(?<n>a\\k<n>)", "(?:a\\1)(b)", "(a|b\\1)+")) {
            assertFailsWith<IllegalArgumentException>(pattern) { SignalFilter.regexLiteral(Regex(pattern)) }
        }
        assertTrue(Regex("^(a|b\\1)+$").matches("aba"))
        val message = assertFailsWith<IllegalArgumentException> { SignalFilter.regexLiteral(Regex("(a|b\\1)+")) }.message!!
        assertFalse("never matches" in message, message)
        assertTrue("repeated group" in message, message)
        assertEquals("/(a)\\1/", SignalFilter.regexLiteral(Regex("(a)\\1")))
        assertEquals("/((a)\\2)/", SignalFilter.regexLiteral(Regex("((a)\\2)")))
        assertEquals("/(a(b)\\2)/", SignalFilter.regexLiteral(Regex("(a(b)\\2)")))
        assertEquals("/(?<n>a)\\k<n>/", SignalFilter.regexLiteral(Regex("(?<n>a)\\k<n>")))
    }
}
