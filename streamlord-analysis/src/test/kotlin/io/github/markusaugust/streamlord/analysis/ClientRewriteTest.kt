package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.client.clientRewrite
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the client's own rewrite (engine.ts:448-449 in 1.0.4) makes of each expression, held
 * against the port. The expected texts are what the client compiled in Chrome.
 */
class ClientRewriteTest {
    @Test
    fun `signals and actions are rewritten as the client rewrites them, and quoted text is left alone`() {
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
                // The text of a template literal that also holds a ${...} is rewritten too.
                "`total: \$total \${\$a}`" to "`total: \$['total'] \${\$['a']}`",
                "`mail @home(x) \${\$a}`" to "`mail __action(\"home\",evt,x) \${\$['a']}`",
                // A quote in a comment opens a string to the client's pattern, and the signal after it stays as written.
                "\$a = 1 // don't\n\$b = 'x'" to "\$['a'] = 1 // don't\n\$b = 'x'",
                "\$a@get('/x')" to "\$['a']__action(\"get\",evt,'/x')",
                "@get" to "@get",
            )
        for ((text, client) in cases) assertEquals(client, clientRewrite(text).js, text)
    }

    @Test
    fun `an error after a rewritten signal is placed in the expression`() {
        val issues = ExpressionValidator().validate("\$items.map(\$i => \$i.name)", returnsValue = true)
        val error = issues.single { it.code == "expression-syntax" }
        assertEquals("=>", "\$items.map(\$i => \$i.name)".substring(error.start, error.end))
    }

    @Test
    fun `a value-returning expression of many kilobytes is judged`() {
        val text = "{" + (0 until 2000).joinToString(", ") { "k$it: {id: $it, name: 'Item $it', tags: ['a', 'b']}" } + "}"
        assertEquals(emptyList(), ExpressionValidator().validate(text, returnsValue = true))
        val quoted = "'" + "a\\'".repeat(3000) + "' + \$x"
        assertEquals(emptyList(), ExpressionValidator().validate(quoted, returnsValue = true))
    }

    // The scans stand in for the client's two regular expressions, which Java's engine overflows on
    // at a few kilobytes. Here the expressions as the client writes them are run on short random
    // texts, where Java's engine gives what the browser's does, and the scans are held to them.
    @Test
    fun `the scans agree with the client's patterns as written`() {
        val random = Random(1004)
        val alphabet =
            listOf(
                "\$",
                "@",
                "(",
                ")",
                "{",
                "}",
                "'",
                "\"",
                "`",
                "\\",
                "/",
                ";",
                "a",
                "b",
                "1",
                "-",
                ".",
                " ",
                "\n",
                "_",
                "\${",
                "=>",
                "get(",
                "function",
            )
        repeat(20000) {
            val text = (0 until random.nextInt(1, 14)).joinToString("") { alphabet[random.nextInt(alphabet.size)] }
            assertEquals(STATEMENT_RE.findAll(text).map { it.range }.toList(), clientStatements(text), text)
            assertEquals(rewriteAsWritten(text), clientRewrite(text).js, text)
        }
    }

    private companion object {
        /** `statementRe`, engine.ts:415-416 in 1.0.4. */
        val STATEMENT_RE =
            Regex(
                """(?:\/(?:\\\/|[^/])*\/|"(?:\\"|[^"])*"|'(?:\\'|[^'])*'|`(?:\\`|[^`])*`|\(\s*(?:(?:function)\s*\(\s*\)|(?:\(\s*\))\s*=>)\s*(?:\{[\s\S]*?\}|[^;){]*)\s*\)\s*\(\s*\)|[^;])+""",
            )

        /** The rewrite, engine.ts:448-449 in 1.0.4. */
        val REWRITE_RE =
            Regex(
                """(?:"[^"\\]*(?:\\.[^"\\]*)*"|'[^'\\]*(?:\\.[^'\\]*)*'|`[^`\\$]*(?:(?:\\.|\$(?!\{))[^`\\$]*)*`)|\$\{([^{}]*)\}|\$(\w+(?:[.-]\w+)*)|@([A-Za-z_$][\w$]*)\(""",
            )
        val SIGNAL_RE = Regex("""\$(\w+(?:[.-]\w+)*)""")
        val ACTION_RE = Regex("""@([A-Za-z_$][\w$]*)\(""")

        fun path(name: String): String = name.split('.').fold("\$") { p, part -> "$p['$part']" }

        fun rewriteAsWritten(expr: String): String =
            REWRITE_RE.replace(expr) { m ->
                val interpolation = m.groups[1]
                val signal = m.groups[2]
                val action = m.groups[3]
                when {
                    interpolation != null -> {
                        val inner =
                            SIGNAL_RE
                                .replace(interpolation.value) { path(it.groupValues[1]) }
                                .let { s -> ACTION_RE.replace(s) { "__action(\"${it.groupValues[1]}\",evt," } }
                        "\${$inner}"
                    }

                    signal != null -> {
                        path(signal.value)
                    }

                    action != null -> {
                        "__action(\"${action.value}\",evt,"
                    }

                    else -> {
                        m.value
                    }
                }
            }
    }
}
