package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.client.clientRewrite
import io.github.markusaugust.streamlord.fixtures.assertGrowsLinearly
import kotlin.test.Test

/**
 * The client's rewrite grows with the expression, not with its square: a scan that searched on to
 * the end of the text at every template literal, or went through the signals of a `${...}` again
 * at each action in it, took from six to nine seconds on texts the size of the largest here.
 */
class ClientRewriteTimingTest {
    @Test
    fun `many short template literals are read once`() {
        // Each with a ${...}, so the work is reading the text rather than copying it.
        assertGrowsLinearly("short template literals", 800, { "`a\${x}b`".repeat(it) }) { clientRewrite(it) }
    }

    @Test
    fun `many references in one interpolation are read once`() {
        assertGrowsLinearly("references in one \${...}", 300, { n -> "`\${" + "\$s + ".repeat(n) + "1}`" }) { text ->
            clientRewrite(text)
        }
    }

    @Test
    fun `an action in an interpolation is not held against every signal in it`() {
        assertGrowsLinearly("signals and actions in one \${...}", 1_500, { n -> "`\${" + "\$s + @a() + ".repeat(n) + "1}`" }) { text ->
            clientRewrite(text)
        }
    }
}
