package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A value chained or nested past what the stack holds: the parser refuses it or reads it, and the
 * checks that read its tree answer either way and never run out of stack themselves.
 */
class ExpressionDepthTest {
    private val analyzer = Analyzer()

    private fun judged(value: String) = analyzer.analyzeHtml("<div data-on:click=\"$value\"></div>").map { it.code }

    @Test
    fun `an argument under many signs`() {
        assertTrue(judged("@get(" + "- ".repeat(DEEP) + "1)").all { it == "expression-syntax" || it == "action-argument" })
    }

    @Test
    fun `an argument of many terms`() {
        assertTrue(judged("@get(" + "1 + ".repeat(DEEP) + "1)").all { it == "expression-syntax" })
    }

    @Test
    fun `options nested in many arrays and objects`() {
        assertTrue(
            judged("@get('/x', " + "[".repeat(DEEP) + "]".repeat(DEEP) + ")").all {
                it == "expression-syntax" ||
                    it == "action-argument"
            },
        )
        assertTrue(
            judged("@get('/x', " + "{retry: ".repeat(DEEP) + "1" + "}".repeat(DEEP) + ")").all {
                it == "expression-syntax" ||
                    it == "option-value"
            },
        )
    }

    private companion object {
        const val DEEP = 100_000
    }
}
