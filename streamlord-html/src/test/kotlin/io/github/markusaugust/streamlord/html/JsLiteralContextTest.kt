package io.github.markusaugust.streamlord.html

import io.github.markusaugust.streamlord.core.SignalsCodecException
import io.github.markusaugust.streamlord.core.json.JsonWriter
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

    // The depth at which JsonWriter refuses a value, counted the same way: a leaf is no level.
    @Test
    fun `values nest as deep as JsonWriter lets them`() {
        val deepest = (1..64).fold<Int, Any?>("x") { inner, _ -> listOf(inner) }
        assertEquals(JsonWriter.write(deepest).count { it == '[' }, JsLiteral.write(deepest).count { it == '[' })
        assertFailsWith<SignalsCodecException> { JsLiteral.write(listOf(deepest)) }
        assertFailsWith<SignalsCodecException> { JsonWriter.write(listOf(deepest)) }
    }

    // UTF-8 carries no half of a surrogate pair: escaped, it reaches the expression as itself.
    @Test
    fun `a lone surrogate is escaped as JsonWriter escapes it`() {
        assertEquals("'a\\ud83d'", JsLiteral.string("a\uD83D"))
        assertEquals("'\\udc00b'", JsLiteral.string("\uDC00b"))
        assertEquals("'\uD83D\uDE00'", JsLiteral.string("\uD83D\uDE00"))
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
