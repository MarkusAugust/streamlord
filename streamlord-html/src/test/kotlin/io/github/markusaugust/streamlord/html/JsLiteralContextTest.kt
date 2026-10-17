package io.github.markusaugust.streamlord.html

import kotlinx.html.div
import kotlinx.html.stream.createHTML
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** JsLiteral where the browser evaluates it: in an object literal, and inside a script element. */
class JsLiteralContextTest {
    @Test
    fun `a __proto__ key stays a key`() {
        // Quoted or bare, __proto__ in an object literal sets the prototype; a computed key does not.
        assertEquals("{['__proto__']: 1, a: 2}", JsLiteral.write(mapOf("__proto__" to 1, "a" to 2)))
    }

    @Test
    fun `a string never opens a script comment`() {
        val written = JsLiteral.string("<!--<script>")
        assertFalse("<!" in written, written)
        assertEquals("'\\x3c!--<script>'", written)
    }

    @Test
    fun `__proto__ is refused as a signal`() {
        assertFailsWith<InvalidSignalNameException> { createHTML().div { dataSignals("__proto__" to 1) } }
    }
}
