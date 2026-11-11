package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.client.clientRewrite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * The client's rewrite grows with the expression, not with its square: a scan that searches on to
 * the end of the text at every template literal, or goes through the signals of a `${...}` again
 * at each action in it, takes from six to nine seconds on these. The bound is generous, for a slow
 * machine; a scan that reads the text once takes some fifty milliseconds.
 */
class ClientRewriteTimingTest {
    private fun assertQuick(
        what: String,
        bound: Duration = 2.seconds,
        block: () -> Unit,
    ) {
        block()
        val took = measureTime(block)
        assertTrue(took < bound, "$what took $took")
    }

    @Test
    fun `many short template literals are read once`() {
        val text = "``".repeat(200_000)
        assertQuick("200000 empty template literals") { assertEquals(200_000, clientRewrite(text).runs.size) }
    }

    @Test
    fun `many references in one interpolation are read once`() {
        val text = "`\${" + "\$s + ".repeat(20_000) + "1}`"
        assertQuick("20000 references in one \${...}") { assertEquals(20_000, clientRewrite(text).rewrites.size) }
    }

    @Test
    fun `an action in an interpolation is not held against every signal in it`() {
        val text = "`\${" + "\$s + @a() + ".repeat(100_000) + "1}`"
        assertQuick("100000 signals and 100000 actions in one \${...}") { assertEquals(200_000, clientRewrite(text).rewrites.size) }
    }
}
