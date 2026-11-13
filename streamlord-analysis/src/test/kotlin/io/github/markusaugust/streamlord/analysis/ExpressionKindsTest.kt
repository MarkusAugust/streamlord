package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** What the highlighting of both editors reads: every part of an expression gets the kind the VS Code grammar gives it. */
class ExpressionKindsTest {
    private fun tokens(
        text: String,
        opaque: List<IntRange> = emptyList(),
    ) = tokenizeExpression(text, opaque).map { text.substring(it.start, it.end) to it.kind }

    @Test
    fun `every part of plain JavaScript has a kind`() {
        assertEquals(
            listOf(
                "foo" to TokenKind.IDENTIFIER,
                "=" to TokenKind.OPERATOR,
                "a" to TokenKind.OBJECT_KEY,
                "Math" to TokenKind.GLOBAL,
                "max" to TokenKind.METHOD,
                "x" to TokenKind.IDENTIFIER,
                "length" to TokenKind.PROPERTY,
                "b" to TokenKind.OBJECT_KEY,
                "go" to TokenKind.METHOD,
                "=>" to TokenKind.KEYWORD,
                "typeof" to TokenKind.KEYWORD,
                "y" to TokenKind.IDENTIFIER,
                "===" to TokenKind.OPERATOR,
                "'s'" to TokenKind.STRING,
                "?" to TokenKind.OPERATOR,
                "c" to TokenKind.IDENTIFIER,
                ":" to TokenKind.OPERATOR,
                "d" to TokenKind.IDENTIFIER,
            ),
            tokens("foo = {a: Math.max(x.length), b: go(() => typeof y === 's' ? c : d)}"),
        )
    }

    @Test
    fun `numbers as JavaScript writes them, and its operators`() {
        assertEquals(
            listOf(
                ".5" to TokenKind.NUMBER,
                "+" to TokenKind.OPERATOR,
                "10n" to TokenKind.NUMBER,
                "~" to TokenKind.OPERATOR,
                "1_000" to TokenKind.NUMBER,
                "^" to TokenKind.OPERATOR,
                "0xFF" to TokenKind.NUMBER,
                "|" to TokenKind.OPERATOR,
                "1e3" to TokenKind.NUMBER,
                "&" to TokenKind.OPERATOR,
                "2" to TokenKind.NUMBER,
                "**" to TokenKind.OPERATOR,
                "500ms" to TokenKind.DURATION,
            ),
            tokens(".5 + 10n ~ 1_000 ^ 0xFF | 1e3 & 2 ** 500ms"),
        )
        assertEquals(listOf("..." to TokenKind.OPERATOR, "\$g" to TokenKind.SIGNAL), tokens("...\$g"))
    }

    @Test
    fun `a slash after a value divides, and comments are comments`() {
        assertEquals(
            listOf(
                "\$a" to TokenKind.SIGNAL,
                "/" to TokenKind.OPERATOR,
                "2" to TokenKind.NUMBER,
                "/" to TokenKind.OPERATOR,
                "\$b" to TokenKind.SIGNAL,
                "/* \$c */" to TokenKind.COMMENT,
                "// \$d @post('/x')" to TokenKind.COMMENT,
            ),
            tokens("\$a / 2 / \$b /* \$c */ // \$d @post('/x')"),
        )
        assertEquals(listOf("// x" to TokenKind.COMMENT, "\$y" to TokenKind.SIGNAL), tokens("// x\n\$y"))
    }

    @Test
    fun `a template literal holds an expression`() {
        assertEquals(
            listOf(
                "`a" to TokenKind.STRING,
                "\${" to TokenKind.TEMPLATE_PUNCTUATION,
                "\$user" to TokenKind.SIGNAL,
                ".name" to TokenKind.SIGNAL_PATH,
                "}" to TokenKind.TEMPLATE_PUNCTUATION,
                "b`" to TokenKind.STRING,
            ),
            tokens("`a\${\$user.name}b`"),
        )
        // A brace of the expression's own does not end it.
        assertEquals(
            listOf(
                "`" to TokenKind.STRING,
                "\${" to TokenKind.TEMPLATE_PUNCTUATION,
                "f" to TokenKind.METHOD,
                "a" to TokenKind.OBJECT_KEY,
                "1" to TokenKind.NUMBER,
                "}" to TokenKind.TEMPLATE_PUNCTUATION,
                "`" to TokenKind.STRING,
            ),
            tokens("`\${f({a: 1})}`"),
        )
    }

    @Test
    fun `a string in HTML's entities is a string`() {
        assertEquals(
            listOf("&quot;x&quot;" to TokenKind.STRING, "+" to TokenKind.OPERATOR, "&#39;y&#39;" to TokenKind.STRING),
            tokens("&quot;x&quot; + &#39;y&#39;"),
        )
    }

    @Test
    fun `a Kotlin template is Kotlin's, and the escaped dollar is a signal`() {
        // "$count + \$total": the first is a template the opaque range covers.
        val text = "\$count + \\\$total + \${'\$'}n"
        assertEquals(
            listOf(
                "+" to TokenKind.OPERATOR,
                "\$total" to TokenKind.SIGNAL,
                "+" to TokenKind.OPERATOR,
                "\${'\$'}" to TokenKind.KOTLIN_DOLLAR_ESCAPE,
                "n" to TokenKind.SIGNAL,
            ),
            tokens(text, listOf(0 until 6)),
        )
        // $$"$a + $${id}": the second is the template.
        assertEquals(listOf("\$a" to TokenKind.SIGNAL, "+" to TokenKind.OPERATOR), tokens("\$a + \$\${id}", listOf(5 until 11)))
    }

    @Test
    fun `a template placeholder is the engine's, and the rest is coloured`() {
        val value = "\${user.name} + \$count + {{ n }}"
        assertEquals(listOf(0 until 12, 24 until 31), templatePlaceholders(value))
        assertEquals(
            listOf("+" to TokenKind.OPERATOR, "\$count" to TokenKind.SIGNAL, "+" to TokenKind.OPERATOR),
            tokens(value, templatePlaceholders(value)),
        )
        // JavaScript's own template literal holds no placeholder.
        assertEquals(emptyList(), templatePlaceholders("`a\${b}`"))
    }

    // An object's colon is punctuation, as the VS Code grammar scopes it; a ternary's is an operator.
    @Test
    fun `the colon after a key is no operator`() {
        assertEquals(
            listOf(
                "a" to TokenKind.OBJECT_KEY,
                "1" to TokenKind.NUMBER,
                "'b'" to TokenKind.STRING,
                "\$c" to TokenKind.SIGNAL,
                "?" to TokenKind.OPERATOR,
                "2" to TokenKind.NUMBER,
                ":" to TokenKind.OPERATOR,
                "3" to TokenKind.NUMBER,
            ),
            tokens("{a: 1, 'b': \$c ? 2 : 3}"),
        )
    }

    @Test
    fun `a signal name is a signal and its path`() {
        assertEquals(
            listOf("user" to TokenKind.SIGNAL, ".name" to TokenKind.SIGNAL_PATH),
            "user.name".let { t ->
                tokenizeSignalName(t).map {
                    t.substring(it.start, it.end) to
                        it.kind
                }
            },
        )
        assertEquals(
            listOf("is-open" to TokenKind.SIGNAL),
            "is-open".let { t ->
                tokenizeSignalName(t).map {
                    t.substring(it.start, it.end) to
                        it.kind
                }
            },
        )
        assertEquals(emptyList(), tokenizeSignalName("not a name"))
    }

    @Test
    fun `a modifier's argument is a number, a duration or a word`() {
        val name = "data-on-intersect__threshold.50__delay.500ms__case.kebab__window"
        val parts = tokenizeAttributeName(name, "data-")!!.map { name.substring(it.start, it.end) to it.part }
        assertEquals(
            listOf(
                "data-" to NamePart.PREFIX,
                "on-intersect" to NamePart.PLUGIN,
                "__" to NamePart.MODIFIER_SIGIL,
                "threshold" to NamePart.MODIFIER,
                "." to NamePart.MODIFIER_ARG_DOT,
                "50" to NamePart.MODIFIER_ARG_NUMBER,
                "__" to NamePart.MODIFIER_SIGIL,
                "delay" to NamePart.MODIFIER,
                "." to NamePart.MODIFIER_ARG_DOT,
                "500ms" to NamePart.MODIFIER_ARG_DURATION,
                "__" to NamePart.MODIFIER_SIGIL,
                "case" to NamePart.MODIFIER,
                "." to NamePart.MODIFIER_ARG_DOT,
                "kebab" to NamePart.MODIFIER_ARG,
                "__" to NamePart.MODIFIER_SIGIL,
                "window" to NamePart.MODIFIER,
            ),
            parts,
        )
        // As the browser lowercases a name, so does the prefix's match.
        assertEquals(NamePart.PLUGIN, tokenizeAttributeName("DATA-SHOW", "data-")!![1].part)
    }

    @Test
    fun `a long line is read in time`() {
        val line = (1..2000).joinToString(" + ") { "\$a / 2 / \$b + {k: {n: [(\$c)]}}" }
        val started = TimeSource.Monotonic.markNow()
        assertTrue(tokenizeExpression(line).isNotEmpty())
        assertTrue(started.elapsedNow().inWholeMilliseconds < 2000, "took ${started.elapsedNow()}")
    }
}
