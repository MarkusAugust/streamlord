package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the client's own rewrite (engine.ts:448 in 1.0.4, run as written in Node) makes of each
 * expression, held against the port. Actions are left out: the analysis turns `@` into `_` first.
 */
class ClientRewriteTest {
    @Test
    fun `signals are rewritten as the client rewrites them, and quoted text is left alone`() {
        val cases =
            listOf(
                "'\$a-b' + `\$c-d \${\$e-f}`" to "'\$a-b' + `\$['c-d'] \${\$['e-f']}`",
                "\$items.map(\$i => \$i.name)" to "\$['items']['map'](\$['i'] => \$['i']['name'])",
                "\"\$x\" + \$y" to "\"\$x\" + \$['y']",
                "`a \${\$x} b`" to "`a \${\$['x']} b`",
                "`\$x`" to "`\$x`",
                "\$foo.0.name + \$['x']" to "\$['foo']['0']['name'] + \$['x']",
                "\$a-\$b" to "\$['a']-\$['b']",
                "x = {\$a: 1}" to "x = {\$['a']: 1}",
                "\$count++ // \$z" to "\$['count']++ // \$['z']",
                "'it\\'s \$x' + \$y" to "'it\\'s \$x' + \$['y']",
                "`\\\${\$x}`" to "`\\\${\$x}`",
                "`\${\$a} and \${\$b}` + `\${ {a:1}.a }`" to "`\${\$['a']} and \${\$['b']}` + `\${ {a:1}.a }`",
            )
        for ((text, client) in cases) assertEquals(client, clientSignals(text).js, text)
    }

    @Test
    fun `an error after a rewritten signal is placed in the expression`() {
        val issues = ExpressionValidator().validate("\$items.map(\$i => \$i.name)", returnsValue = true)
        val error = issues.single { it.code == "expression-syntax" }
        assertEquals("=>", "\$items.map(\$i => \$i.name)".substring(error.start, error.end))
    }

    // The scan stands in for the client's one regular expression, which Java's engine overflows on at a
    // few kilobytes; it was held to that expression over 4000 random inputs when it was written.
    @Test
    fun `a value-returning expression of many kilobytes is judged`() {
        val text = "{" + (0 until 2000).joinToString(", ") { "k$it: {id: $it, name: 'Item $it', tags: ['a', 'b']}" } + "}"
        assertEquals(emptyList(), ExpressionValidator().validate(text, returnsValue = true))
        val quoted = "'" + "a\\'".repeat(3000) + "' + \$x"
        assertEquals(emptyList(), ExpressionValidator().validate(quoted, returnsValue = true))
    }
}
