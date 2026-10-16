package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.domain.ExpressionGuard
import io.github.markusaugust.streamlord.core.domain.InterpolatedExpressionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Residues of an eaten signal that are only operators or an operator with a missing side, and expressions that are neither. */
class ExpressionGuardResiduesTest {
    @Test
    fun `residues of eaten signals are refused`() {
        for ((residue, written) in listOf(
            ";" to "\$a;\$b",
            "@post('/x');  = true" to "@post('/x'); \$done = true",
            " in " to "\$key in \$map",
            "typeof " to "typeof \$x",
            "void " to "void \$x",
            "x in" to "x in \$map",
            "instanceof Foo" to "\$x instanceof Foo",
            "+ 1" to "\$a + 1",
            "- 1" to "\$a - 1",
            "`total: \${}`" to "`total: \${\$n}`",
        )) {
            assertFailsWith<InterpolatedExpressionException>(
                "should refuse what $written becomes: \"$residue\"",
            ) { ExpressionGuard.check(residue) }
        }
    }

    @Test
    fun `literals are read as JavaScript reads them`() {
        for (ok in listOf(
            "/'/.test(\$s) && \$t == 'x'",
            "\$s.replace(/\"/g, '')",
            "'//not a comment' + \$a",
            "if (\$a) { \$b = 1; }",
            "\$a; \$b",
            "typeof \$x === 'string'",
            "\$key in \$map",
            "-1",
            "+\$n",
            "\$items.renew",
            "`total: \${\$n}`",
            "\$msg = 'Use \${} for templates'",
            "\$a = 1; .5 + \$b",
            "return /;?\$/.test(\$v)",
        )) {
            assertEquals(ok, ExpressionGuard.check(ok))
        }
    }
}
