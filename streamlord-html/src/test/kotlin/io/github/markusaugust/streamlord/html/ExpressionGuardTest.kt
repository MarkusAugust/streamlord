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
    private val eaten = mapOf(
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
    )

    @Test
    fun `the traces of an interpolated signal are refused with a helpful message`() {
        for ((received, written) in eaten) {
            val e = assertFailsWith<InterpolatedExpressionException>("should refuse what $written becomes: \"$received\"") {
                ExpressionGuard.check(received)
            }
            assertTrue(e.message!!.contains("signal(\"count\")"), e.message)
            assertTrue(e.message!!.contains("\$\$\""), e.message)
        }
    }

    @Test
    fun `working expressions pass untouched`() {
        for (ok in listOf(
            "\$count++", "++\$count", "--\$n", "-1", "!\$open", "\$open = !\$open", "\$user.name",
            "@post('/x')", "@post('/x', {contentType: 'form'})", "evt.key === 'Escape' && (\$open = false)",
            "el.value", "\$items.length > 0 ? 'some' : 'none'", "console.log(patch)", "\$a + \$b", "'x'", "42", "{a: 1}", "[1, 2]",
            "window.location.href = '/x'", "\$count = 0; \$open = false", "@peek(() => \$a.b)",
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
}
