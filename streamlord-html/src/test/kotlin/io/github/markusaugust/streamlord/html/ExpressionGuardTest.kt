package io.github.markusaugust.streamlord.html

import io.github.markusaugust.streamlord.core.domain.ExpressionGuard
import io.github.markusaugust.streamlord.core.domain.InterpolatedExpressionException
import kotlinx.html.button
import kotlinx.html.div
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExpressionGuardTest {
    /** What the DSL receives when a Kotlin template ate the signal, and what the author wrote. */
    private val eaten =
        mapOf(
            "" to "\"\$count\"",
            "++" to "\"\$count++\"",
            "--" to "\"\$count--\"",
            " = !" to "\"\$open = !\$open\"",
            ".name" to "\"\$user.name\"",
            " + " to "\"\$a + \$b\"",
            "@post('/x') && " to "\"@post('/x') && \$ok\"",
            " === 'x'" to "\"\$mode === 'x'\"",
            " ? 'a' : 'b'" to "\"\$on ? 'a' : 'b'\"",
            "evt.key === 'Escape' && ( = false)" to "\"evt.key === 'Escape' && (\$open = false)\"",
            "; ++" to "\"\$a; \$b++\"",
            " < 10" to "\"\$count < 10\"",
            "'Hello, ' + " to "\"'Hello, ' + \$name\"",
            "\$total - " to "\"\$total - \$discount\"",
            "@post('/x', {count: })" to "\"@post('/x', {count: \$count})\"",
            "{n: , m: 1}" to "\"{n: \$seed, m: 1}\"",
            "[, 1]" to "\"[\$first, 1]\"",
            "\$a && " to "\"\$a && \$b\"",
        )

    @Test
    fun `the traces of an interpolated signal are refused with a helpful message`() {
        for ((received, written) in eaten) {
            val e =
                assertFailsWith<InterpolatedExpressionException>("should refuse what $written becomes: \"$received\"") {
                    ExpressionGuard.check(received)
                }
            assertTrue(e.message!!.contains("signal(\"count\")"), e.message)
            assertTrue(e.message!!.contains("\$\$\""), e.message)
        }
    }

    @Test
    fun `working expressions pass untouched`() {
        for (ok in listOf(
            "\$count++",
            "++\$count",
            "--\$n",
            "-1",
            "!\$open",
            "\$open = !\$open",
            "\$user.name",
            "@post('/x')",
            "@post('/x', {contentType: 'form'})",
            "evt.key === 'Escape' && (\$open = false)",
            "el.value",
            "\$items.length > 0 ? 'some' : 'none'",
            "console.log(patch)",
            "\$a + \$b",
            "'x'",
            "42",
            "{a: 1}",
            "[1, 2]",
            "[1, 2,]",
            "fn(a, )",
            "{a, b}",
            "(x++)",
            "(--\$n)",
            "\$count ++",
            "\$n --",
            "\$a; \$b ++",
            "\$name.replace(/-/g, ' ')",
            "\$tags = \$input.split(/[,;]/)",
            "\$s.replace(/(?:a|b)/g, '')",
            "\$x.match(/[<>]/)",
            "\$re = /^(=|:)/",
            "\$a / \$b",
            "(\$a + \$b) / 2",
            "!\$email.match(/^\\S+@\\S+$/)",
            "\$s.split(/,/)",
            "/^[a-z]+$/.test(\$slug)",
            "/\\d+/.exec(\$id)[0]",
            "\$mood = ':)'",
            "\$x = 'a, ' + \$y",
            "@post('/x', {q: '=)'})",
            "\$a ? 1 : 2",
            "@setAll(false, {include: /^menu\\./})",
            "{include: /re/, exclude: /x/}",
            "[]",
            "{}",
            "[{}]",
            "Math.max(...\$items)",
            "@post('/x', {...\$extra})",
            "(.5 * \$x)",
            ".5 * \$x",
            "...\$rest",
            "window.location.href = '/x'",
            "\$count = 0; \$open = false",
            "@peek(() => \$a.b)",
        )) {
            assertEquals(ok, ExpressionGuard.check(ok))
        }
    }

    @Test
    fun `the guard sits in the DSL`() {
        val e = assertFailsWith<InterpolatedExpressionException> { elements { button { dataOnClick("++") } } }
        assertEquals("++", e.expression)
        assertFailsWith<InterpolatedExpressionException> { elements { div { dataText("") } } }
        assertFailsWith<InterpolatedExpressionException> { elements { div { dataOn("keydown", " = !") } } }
        assertFailsWith<InterpolatedExpressionException> { elements { div { dataComputed("double", " * 2") } } }
    }

    @Test
    fun `multi-dollar strings keep their signals`() {
        // Kotlin 2.2+: in a $$"..." literal a single $ is just a character.
        assertEquals("""<button data-on:click="${'$'}count++"></button>""", elements { button { dataOnClick($$"$count++") } })
        assertEquals("""<div data-text="${'$'}user.name"></div>""", elements { div { dataText($$"$user.name") } })
    }

    @Test
    fun `comments, a regex after an arrow and a number ending in a dot are working expressions`() {
        for (ok in listOf("// raise it\n\$count++", "\$x /* trailing */", "x => /a/", "\$n = 1.")) {
            assertEquals(ok, ExpressionGuard.check(ok))
        }
    }

    // A comment is not an operand, so an expression that is only a comment and an operator is still eaten.
    @Test
    fun `a comment does not hide an eaten signal`() {
        assertFailsWith<InterpolatedExpressionException> { ExpressionGuard.check("/* raise */ ++") }
    }
}
