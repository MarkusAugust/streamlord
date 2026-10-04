package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals

/** The cases of the VS Code extension's `expression.test.ts`. */
class ExpressionTest {
    private val analyzer = Analyzer()

    private fun expr(text: String) = analyzer.validateExpression(text)

    private fun codes(issues: List<Issue>) = issues.map { it.code }

    @Test
    fun `accepts a numeric segment in a signal path`() {
        assertEquals(emptyList(), expr($$"$foo.0.name"))
        assertEquals(emptyList(), expr($$"$items.12.title + $a.b.0 + 1.5"))
        assertEquals(listOf("expression-syntax"), codes(expr($$"$foo.0.name +")))
    }

    @Test
    fun `leaves an action name in a string a template literal or a comment alone`() {
        assertEquals(emptyList(), expr($$"$msg = 'mail me @home (please)'"))
        assertEquals(emptyList(), expr($$"$b = `x @foo(y)`"))
        assertEquals(emptyList(), expr($$"$a = 1 // @nope('x')"))
        assertEquals(emptyList(), expr($$"/* @nada(1) */ $a = 1"))
        assertEquals(listOf("unknown-action"), codes(expr($$"`${@bogus('/y')}`")))
    }

    @Test
    fun `reports a space between an action and its parenthesis`() {
        val src = "@get ('/x')"
        val issue = expr(src).single()
        assertEquals("action-space", issue.code)
        assertEquals("@get ", src.substring(issue.start, issue.end))
        assertEquals("@get('/x')", issue.fixes[0].apply(src))
        assertEquals(emptyList(), expr("@get('/x')"))
    }

    @Test
    fun `camel-cases a capital after the hyphen in the signal fix`() {
        val src = $$"$a-B"
        assertEquals($$"$aB", expr(src).single().fixes[0].apply(src))
    }

    @Test
    fun `judges a hyphenated signal in the code of a template literal and not in its text`() {
        val src = $$"'$a-b' + `$c-d ${$e-f}`"
        val issues = expr(src)
        assertEquals(listOf("signal-kebab"), codes(issues))
        assertEquals($$"$e-f", src.substring(issues[0].start, issues[0].end))
    }
}
