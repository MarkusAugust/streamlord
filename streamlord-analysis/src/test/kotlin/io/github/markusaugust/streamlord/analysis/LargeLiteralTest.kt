package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One literal with thousands of holes is judged in time that grows with the literal, not with its
 * square: a fix that rewrites the whole literal is made only when it is applied, and once for all
 * the holes that offer it. largeLiteral.test.ts holds the VS Code analysis to the same.
 */
class LargeLiteralTest {
    @Test
    fun `a literal with thousands of holes is judged in a few seconds at most, its fixes made only when applied`() {
        val rows = (0 until 1000).joinToString("") { "<p title=\"\$q\" data-text=\"\$k\" class=\"\${q.trim()}\">\$q</p>\n" }
        val src = "fun f(q: String, k: Int): String {\n    // language=HTML\n    return \"\"\"<div>$rows</div>\"\"\"\n}\n"
        val started = System.nanoTime()
        val issues = Analyzer().analyzeKotlin(src, AnalyzeOptions("data-", true))
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals(3000, issues.count { it.code == "unescaped-interpolation" })
        assertEquals(1000, issues.count { it.code == "kotlin-interpolation" })
        assertTrue(ms < 10_000, "took $ms ms")
        val dollars = issues.first { it.code == "kotlin-interpolation" }.fixes.first { it.title.startsWith("Make it") }
        val expected =
            "\$\$\"\"\"<div><p title=\"\$\$q\" data-text=\"\$k\" class=\"\$\${q.trim()}\">\$\$q</p>\n" +
                "<p title=\"\$\$q\" data-text=\"\$\$k\""
        assertTrue(expected in dollars.apply(src))
    }
}
