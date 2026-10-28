package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertContains
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
        // The browser lowercases the key, so a-B reaches the client as a-b, which is the signal aB.
        val src = $$"$a-B"
        val issue = expr(src).single()
        assertEquals($$"$aB", issue.fixes[0].apply(src))
        assertContains(issue.message, $$"a key a-b is the signal $aB")
    }

    @Test
    fun `names the signal a kebab-case key gives as the client cases it`() {
        // `caseFns.camel` in text.ts (1.0.4): only a lowercase letter after a hyphen is raised.
        fun clientCamel(key: String) = Regex("-[a-z]").replace(key) { it.value[1].uppercase() }
        for (key in listOf("a-b-1", "grid-col-2", "foo-bar")) {
            val src = "$" + key
            val issue = expr(src).single()
            val signal = clientCamel(key)
            assertContains(issue.message, "a key $key is the signal \$$signal", message = src)
            assertEquals(listOf("$" + signal, "$" + key.replace("-", " - ")), issue.fixes.map { it.apply(src) }, src)
        }
        // A key the client leaves as written is reached by the $ reference as written, so there is nothing to change it to.
        assertEquals("foo-_bar", clientCamel("foo-_bar"))
        val same = expr($$"$foo-_bar").single()
        assertEquals("signal-kebab", same.code)
        assertContains(same.message, "a key foo-_bar keeps that name")
        assertEquals(listOf($$"$foo - _bar"), same.fixes.map { it.text })
    }

    @Test
    fun `judges a hyphenated signal in the code of a template literal and tells that the client rewrites its text`() {
        val src = $$"'$a-b' + `$c-d ${$e-f}`"
        val issues = expr(src)
        // The literal holds a ${...}, so the client's pattern does not step over it: $c-d in its text becomes $['c-d'].
        assertEquals(listOf("literal-rewritten", "signal-kebab"), codes(issues))
        assertEquals($$"$c-d", src.substring(issues[0].start, issues[0].end))
        assertEquals($$"$e-f", src.substring(issues[1].start, issues[1].end))
        assertEquals(emptyList(), expr($$"`$c-d`"))
    }
}
