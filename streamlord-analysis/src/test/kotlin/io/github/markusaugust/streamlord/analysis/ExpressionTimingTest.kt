package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.fixtures.assertGrowsLinearly
import kotlin.test.Test

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
        ) { analyzer.analyzeHtml(it, AnalyzeOptions("data-star-", true)) }
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
}
