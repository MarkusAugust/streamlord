package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.fixtures.assertGrowsLinearly
import kotlin.test.Test
import kotlin.test.assertTrue

/** An expression of a few thousand action calls is judged in time that grows with its length. */
class ExpressionTimingTest {
    private val analyzer = Analyzer()

    @Test
    fun `an expression of many action calls`() {
        assertGrowsLinearly(
            "action calls",
            20,
            { n -> (0 until n).joinToString("; ") { "@get('/x/$it')" } },
        ) { analyzer.validateExpression(it) }
    }

    // The parser reads the arguments, the options and the values below: each grows with its text.
    @Test
    fun `an action call with many arguments and an options object of many keys`() {
        assertGrowsLinearly(
            "arguments and option keys",
            20,
            { n -> "<div data-on:click=\"@post('/x', {" + (0 until n).joinToString(", ") { "retry$it: [1, {a: 'b'}]" } + "})\"></div>" },
        ) { analyzer.analyzeHtml(it) }
    }

    @Test
    fun `a computed object of many values and a filter of many keys`() {
        assertGrowsLinearly(
            "computed values and filter keys",
            20,
            { n ->
                "<div data-computed=\"{" + (0 until n).joinToString(", ") { "v$it: [1, 2 + \$a]" } + "}\" data-json-signals=\"{" +
                    (0 until n).joinToString(", ") { "include$it: /a/" } + "}\"></div>"
            },
        ) { analyzer.analyzeHtml(it) }
    }

    @Test
    fun `an expression written as one value with many commas`() {
        assertGrowsLinearly(
            "comma operator",
            20,
            { n ->
                "import io.github.markusaugust.streamlord.html.*\nfun f() = div { dataOnClick(postExpr(\"'/a', " +
                    (0 until n).joinToString(", ") { "\$x$it" } +
                    "\")) }"
            },
        ) { analyzer.analyzeKotlin(it) }
    }

    @Test
    fun `a datastar-fetch handler of many switches over the event's type`() {
        assertGrowsLinearly(
            "switches over evt.detail.type",
            20,
            { n -> "<div data-on:datastar-fetch=\"" + (0 until n).joinToString("; ") { "switch (evt.detail.type) { case 'nope$it': 1 }" } + "\"></div>" },
        ) { analyzer.analyzeHtml(it) }
    }

    // As deep as the readers before the parser fed them read, on a thread of 1 MB.
    @Test
    fun `nested actions and options objects as deep as before`() {
        var said = emptyList<String>()
        val nested = "@get(".repeat(600) + "1" + ")".repeat(600)
        val options = "@get('/x', {headers: " + "{a: ".repeat(400) + "1" + "}".repeat(400) + ", retyr: 1})"
        val thread =
            Thread(null, {
                said = listOf(nested, options).flatMap { v -> analyzer.analyzeHtml("<div data-on:click=\"$v\"></div>").map { it.message } }
            }, "deep", 1L shl 20)
        thread.start()
        thread.join()
        assertTrue(said.none { "stack" in it }, said.toString())
        assertTrue(said.any { "has no option retyr" in it }, said.toString())
    }

    @Test
    fun `an expression of many action calls with options`() {
        assertGrowsLinearly(
            "action calls with options",
            20,
            { n -> (0 until n).joinToString("; ") { "@post('/x', {retry: 'never', retryInterval: $it, filterSignals: {include: /a/}})" } },
        ) { analyzer.validateExpression(it) }
    }

    @Test
    fun `a computed of many assignments and updates`() {
        assertGrowsLinearly(
            "computed side effects",
            20,
            { n -> "<div data-computed:a=\"" + (0 until n).joinToString(" + ") { "(\$b$it = 1) + ++  \$c$it" } + "\"></div>" },
        ) { analyzer.analyzeHtml(it) }
    }

    @Test
    fun `a tag of many plain attributes under an alias, and an indicator after an init`() {
        assertGrowsLinearly(
            "attributes under an alias",
            20,
            { n ->
                "<div data-init=\"@get('/x')\" " + (0 until n).joinToString(" ") { "data-on:e$it=\"\$a$it\" data-text=\"\$b\"" } +
                    " data-indicator:i></div>"
            },
        ) { analyzer.analyzeHtml(Source(it, SourceLanguage.HTML, "data-star-")) }
    }

    @Test
    fun `a file of DSL calls with modifiers`() {
        assertGrowsLinearly(
            "modifier lambdas",
            20,
            { n ->
                "import io.github.markusaugust.streamlord.html.*\nfun DIV.f() {\n" +
                    (0 until n).joinToString(
                        "\n",
                    ) { "    dataOnInterval(\"@get('/t')\") { leading = true; viewTransition = \"x\" == \"y\" }" } +
                    "\n}"
            },
        ) { analyzer.analyzeKotlin(it) }
    }

    @Test
    fun `an init of many functions nested in one another, before an indicator`() {
        assertGrowsLinearly(
            "function bodies",
            20,
            { n -> "<div data-init=\"" + "f(() => ".repeat(n) + "@get('/x')" + ")".repeat(n) + "\" data-indicator=\"busy\"></div>" },
        ) { analyzer.analyzeHtml(it) }
    }
}
