package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A value chained or nested deep. What the parser reads, the checks judge by its tree as they judge
 * a shallow one; past what the stack holds the parser refuses it or reads it, and nothing is thrown
 * out of the analysis either way.
 */
class ExpressionDepthTest {
    private val analyzer = Analyzer()

    private fun judged(value: String) = analyzer.analyzeHtml("<div data-on:click=\"$value\"></div>").map { it.code }

    @Test
    fun `a value the parser reads is judged at its depth`() {
        assertEquals(listOf(IssueCode.ACTION_ARGUMENT), judged("@get(" + "- ".repeat(READ) + "1)"))
        assertEquals(listOf(IssueCode.ACTION_ARGUMENT), judged("@get('/x', " + "[".repeat(READ) + "]".repeat(READ) + ")"))
        assertEquals(listOf(IssueCode.OPTION_VALUE), judged("@get('/x', {retry: " + "(".repeat(READ) + "1" + ")".repeat(READ) + "})"))
        assertEquals(emptyList(), judged("@get(" + "1 + ".repeat(READ) + "1)"))
    }

    @Test
    fun `a value past what the stack holds is refused or judged, and never thrown`() {
        val refused = listOf(IssueCode.EXPRESSION_SYNTAX)
        for (value in listOf(
            "@get(" + "- ".repeat(DEEP) + "1)",
            "@get('/x', " + "[".repeat(DEEP) + "]".repeat(DEEP) + ")",
            "@get('/x', " + "{retry: ".repeat(DEEP) + "1" + "}".repeat(DEEP) + ")",
        )) {
            val codes = judged(value)
            assertTrue(codes == refused || codes.all { it == IssueCode.ACTION_ARGUMENT || it == IssueCode.OPTION_VALUE }, codes.toString())
        }
        assertTrue(judged("@get(" + "1 + ".repeat(DEEP) + "1)").let { it.isEmpty() || it == refused })
    }

    private companion object {
        const val READ = 200
        const val DEEP = 100_000
    }
}
