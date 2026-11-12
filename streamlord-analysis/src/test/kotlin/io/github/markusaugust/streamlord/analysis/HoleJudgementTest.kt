package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * What the corpus cannot hold: the editor's own facts about a hole, and the time a large file
 * takes.
 */
class HoleJudgementTest {
    private val analyzer = Analyzer()

    private fun interpolation(
        src: String,
        opts: AnalyzeOptions,
    ) = analyzer.analyzeKotlin(src, opts).filter { it.code == "unescaped-interpolation" }.map { it.severity }

    @Test
    fun `the editor's fact wins over what the text shows`() {
        // The text takes both for the author's own: a literal of markup, a workspace constant.
        val src = "fun f(b: Boolean) = \"<p>\${if (b) \"<b>x</b>\" else \"<i>y</i>\"} \$LABEL</p>\""
        val text = AnalyzeOptions(constants = setOf("LABEL"))
        assertEquals(emptyList(), interpolation(src, text))
        val typed = text.copy(valueFact = { _, _ -> ValueFact.STRING })
        assertEquals(listOf(Severity.WARNING, Severity.WARNING), interpolation(src, typed))
    }

    /** The issues on [src] where the editor says the last [value] in it is [fact], as code and the text they cover. */
    private fun told(
        src: String,
        value: String,
        fact: ValueFact,
    ): List<Pair<String, String>> {
        val at = src.lastIndexOf(value)
        val opts = AnalyzeOptions(valueFact = { start, end -> if (start == at && end == at + value.length) fact else null })
        return analyzer.analyzeKotlin(src, opts).map { (it.code ?: "") to src.substring(it.start, it.end) }
    }

    @Test
    fun `a Trusted the editor resolves to another package is no markup the text reads in place`() {
        // A star import leaves the name Streamlord's to the text, which reads the literal as an open <a href=.
        val src = "import com.acme.*\n\nfun f(x: String) = \"<div>\${Trusted(\"<a href=\")}\$x</div>\""
        assertEquals(listOf("unsafe-interpolation" to "\$x"), told(src, "", ValueFact.STRING))
        assertEquals(
            listOf("unescaped-interpolation" to "\${Trusted(\"<a href=\")}", "unescaped-interpolation" to "\$x"),
            told(src, "\${Trusted(\"<a href=\")}", ValueFact.OTHER),
        )
    }

    @Test
    fun `a Trusted the editor resolves to another package is not let into a script`() {
        val src = "import com.acme.*\n\nfun f(x: String) = interpolate(\"<script>%s</script>\", Trusted(x))"
        assertEquals(emptyList(), told(src, "", ValueFact.STRING))
        assertEquals(listOf("unsafe-interpolation" to "%s"), told(src, "Trusted(x)", ValueFact.OTHER))
    }

    @Test
    fun `a value in a Datastar expression or a script is sent to an attribute read with el dataset, never to a signal`() {
        // A String written into data-signals is refused by the same rule, so a signal is no way out.
        val sources =
            listOf(
                "fun f(q: String) = \"<p data-show=\\\"\${q}\\\">x</p>\"",
                "fun f(q: String) = interpolate(\"<p data-text=\\\"%s\\\">x</p>\", q)",
                "fun f(q: String) = interpolate(\"<p data-text=\\\"\${q}\\\">x</p>\")",
                "fun f(q: String) = interpolate(\"<script>%s</script>\", q)",
                "fun f(q: String) = \"<script>\$q</script>\"",
            )
        for (src in sources) {
            val message = analyzer.analyzeKotlin(src).single().message
            assertTrue("in a signal" !in message && "or a signal" !in message, message)
            if ("script" !in src) assertTrue("el.dataset" in message, message)
        }
    }

    @Test
    fun `the editor is asked about a value without the comment after it`() {
        // Only the editor knows the value is a number, which a style takes.
        val src = "fun f(n: Any) = interpolate(\"<div style=\\\"%s\\\"></div>\", n /* id */ // id\n)"
        assertEquals(listOf("unsafe-interpolation" to "%s"), told(src, "", ValueFact.NUMBER))
        assertEquals(emptyList(), told(src, "n", ValueFact.NUMBER))
    }

    @Test
    fun `a file of two thousand functions with HTML is analysed in a moment`() {
        val q = "\"\"\""
        val src =
            buildString {
                append("package p\n")
                for (i in 0 until 2000) {
                    append("fun f$i(q: String, k: Int): String {\n    val x = 1\n    // language=HTML\n")
                    append("    return $q<div title=\"\$q\"><p>\${k}</p><a href=\"/x/\$q\">y</a></div>$q\n}\n")
                }
            }
        // Warm the JIT, then time one pass of a fresh copy, which nothing of the first is cached for.
        analyzer.analyzeKotlin(src)
        val copy = src.replace("package p", "package q")
        val started = TimeSource.Monotonic.markNow()
        val issues = analyzer.analyzeKotlin(copy)
        val ms = started.elapsedNow().inWholeMilliseconds
        assertEquals(4000, issues.count { it.code == "unescaped-interpolation" || it.code == "unsafe-interpolation" })
        println("two thousand functions: $ms ms")
        assertTrue(ms < 3000, "took $ms ms")
    }
}
