package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the corpus cannot hold: the editor's own facts about a hole, and the time a large file
 * takes. `holeJudgement.test.ts` holds the VS Code twin to the same.
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
        val typed = text.copy(holeFact = { _, _ -> HoleFact.STRING })
        assertEquals(listOf(Severity.WARNING, Severity.WARNING), interpolation(src, typed))
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
        val started = System.nanoTime()
        val issues = analyzer.analyzeKotlin(copy)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals(4000, issues.count { it.code == "unescaped-interpolation" || it.code == "unsafe-interpolation" })
        println("two thousand functions: $ms ms")
        assertTrue(ms < 3000, "took $ms ms")
    }
}
