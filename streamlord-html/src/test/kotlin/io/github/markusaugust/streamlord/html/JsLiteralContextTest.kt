package io.github.markusaugust.streamlord.html

import io.github.markusaugust.streamlord.core.SignalsCodecException
import kotlinx.html.div
import kotlinx.html.stream.createHTML
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** JsLiteral where the browser evaluates it: in an object literal, and inside a script element. */
class JsLiteralContextTest {
    // JsonWriter's guards: a map that holds itself overflowed the stack, and 1 and "1" were both written as '1'.
    @Test
    fun `a cycle or two keys written alike are refused as JsonWriter refuses them`() {
        val cyclic = HashMap<String, Any?>()
        cyclic["self"] = cyclic
        assertFailsWith<SignalsCodecException> { JsLiteral.write(cyclic) }
        assertFailsWith<SignalsCodecException> { JsLiteral.write(mapOf(1 to "a", "1" to "b")) }
        assertEquals("{a: [[1]]}", JsLiteral.write(mapOf("a" to listOf(listOf(1)))))
    }

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
