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
}
