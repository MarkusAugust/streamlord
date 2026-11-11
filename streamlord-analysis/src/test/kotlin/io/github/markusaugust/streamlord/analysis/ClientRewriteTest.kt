package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.client.clientRewrite
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the client's own rewrite (engine.ts, `genRx`, in 1.0.5) makes of each expression, held
 * against the port. The expected texts are what the client's pattern gives; what the client
 * compiled from 364 expressions is in `conformance/client/expressions.json`, and `ClientOracleTest`
 * holds the port to it.
 */
class ClientRewriteTest {
    @Test
    fun `signals and actions are rewritten as the client rewrites them, and quoted text is left alone`() {
        val cases =
            listOf(
                "'\$a-b' + `\$c-d \${\$e-f}`" to "'\$a-b' + `\$c-d \${\$['e-f']}`",
                "\$items.map(\$i => \$i.name)" to "\$['items']['map'](\$['i'] => \$['i']['name'])",
                "\"\$x\" + \$y" to "\"\$x\" + \$['y']",
                "`a \${\$x} b`" to "`a \${\$['x']} b`",
                "`\$x`" to "`\$x`",
                "\$foo.0.name + \$['x']" to "\$['foo']['0']['name'] + \$['x']",
                "\$a-\$b" to "\$['a']-\$['b']",
                "x = {\$a: 1}" to "x = {\$['a']: 1}",
                "\$count++ // \$z" to "\$['count']++ // \$['z']",
                "'it\\'s \$x' + \$y" to "'it\\'s \$x' + \$['y']",
                // The client replaces every ${...} in the literal it matched, one after a backslash too.
                "`\\\${\$x}`" to "`\\\${\$['x']}`",
                "`\${\$a} and \${\$b}` + `\${ {a:1}.a }`" to "`\${\$['a']} and \${\$['b']}` + `\${ {a:1}.a }`",
                // The text of a template literal stays as written, whatever its ${...} hold.
                "`total: \$total \${\$a}`" to "`total: \$total \${\$['a']}`",
                "`mail @home(x) \${\$a}`" to "`mail @home(x) \${\$['a']}`",
                // A ${...} with a brace inside keeps the pattern from matching the literal, whose text is then read as code.
                "`total: \$total \${ {a: 1}.a }`" to "`total: \$['total'] \${ {a: 1}.a }`",
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

    // The first differential seldom draws a template literal the client matches whole with a reference
    // in a ${...}. These texts are built around one: one to three ${...} holding signals and
    // actions, escapes, a ${ after a backslash, a ${ holding a brace, backticks inside, and the
    // line terminators the client's `.` does not match.
    @Test
    fun `the scan agrees with the client's pattern on template literals with interpolations`() {
        val random = Random(1005)

        fun pick(pieces: List<String>): String = pieces[random.nextInt(pieces.size)]

        fun some(
            pieces: List<String>,
            most: Int,
        ): String = (0 until random.nextInt(most + 1)).joinToString("") { pick(pieces) }
        val references = listOf("\$a", "\$b.c", "\$a-b", "\$0", "@get(", "@x(\$a", "@\$a(", "\$a@get(")
        val inner = references + listOf(")", " ", " + ", "1", "a", "'", "\"", "\$", "@", ".", "-", "\n", "\r", " ", " ")
        val rare = listOf("{", "}", "{a: \$a}", "`", "`\$a`", "\\", "/* } */", "'}'")
        val escapes = listOf("\\`", "\\\\", "\\\$", "\\\${", "\\n", "\\{")
        val words = listOf("a", "b ", " ", "\$a", "\$a.b", "\$", "@get(", "'", "\"", "{", "}", "\n", "\r", " ", " ")
        val code =
            references + listOf(")", " ", " + ", "; ", "'", "\"", "'s \$a'", "\"\$b\"", "// ", "/* ", " */", "{", "}", "\n", "\\", "1")

        fun interpolation(): String =
            "\${" +
                (0 until random.nextInt(1, 5)).joinToString("") {
                    when (random.nextInt(20)) {
                        0 -> pick(rare)
                        in 1..10 -> pick(references)
                        else -> pick(inner)
                    }
                } + "}"

        fun text(): String = if (random.nextInt(4) == 0) pick(escapes) else some(words, 3)

        fun template(): String =
            "`" + text() + (0 until random.nextInt(1, 4)).joinToString("") { interpolation() + text() } +
                (if (random.nextInt(25) == 0) "" else "`")

        var matched = 0
        val count = 20000
        repeat(count) {
            val text = some(code, 6) + template() + some(code, 6) + (if (random.nextInt(5) == 0) template() else "")
            if (REWRITE_RE.findAll(text).any { m ->
                    m.value.startsWith("`") &&
                        INTERPOLATION_RE.findAll(m.value).any { REFERENCE_RE in it.value }
                }
            ) {
                matched++
            }
            assertEquals(rewriteAsWritten(text), clientRewrite(text).js, text)
        }
        assertTrue(matched > count / 2, "$matched of $count texts held a matched template literal with a reference in a \${...}")
    }

    private companion object {
        /** `statementRe` of `genRx`, engine.ts in 1.0.5. */
        val STATEMENT_RE =
            Regex(
                """(?:\/(?:\\\/|[^/])*\/|"(?:\\"|[^"])*"|'(?:\\'|[^'])*'|`(?:\\`|[^`])*`|\(\s*(?:(?:function)\s*\(\s*\)|(?:\(\s*\))\s*=>)\s*(?:\{[\s\S]*?\}|[^;){]*)\s*\)\s*\(\s*\)|[^;])+""",
            )

        /** The rewrite of `genRx`, engine.ts in 1.0.5. */
        val REWRITE_RE =
            Regex(
                """(?:"[^"\\]*(?:\\.[^"\\]*)*"|'[^'\\]*(?:\\.[^'\\]*)*'|`(?:\\.|[^`\\$]|\$(?!\{)|\$\{[^{}]*\})*`)|\$(\w+(?:[.-]\w+)*)|@([A-Za-z_$][\w$]*)\(""",
            )
        val INTERPOLATION_RE = Regex("""\$\{([^{}]*)\}""")
        val SIGNAL_RE = Regex("""\$(\w+(?:[.-]\w+)*)""")
        val ACTION_RE = Regex("""@([A-Za-z_$][\w$]*)\(""")
        val REFERENCE_RE = Regex("""\$\w|@[A-Za-z_$][\w$]*\(""")

        fun path(name: String): String = name.split('.').fold("\$") { p, part -> "$p['$part']" }

        fun rewriteAsWritten(expr: String): String =
            REWRITE_RE.replace(expr) { m ->
                val signal = m.groups[1]
                val action = m.groups[2]
                when {
                    m.value.startsWith("`") -> {
                        INTERPOLATION_RE.replace(m.value) { inner ->
                            val rewritten =
                                SIGNAL_RE
                                    .replace(inner.groupValues[1]) { path(it.groupValues[1]) }
                                    .let { s -> ACTION_RE.replace(s) { "__action(\"${it.groupValues[1]}\",evt," } }
                            "\${$rewritten}"
                        }
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
