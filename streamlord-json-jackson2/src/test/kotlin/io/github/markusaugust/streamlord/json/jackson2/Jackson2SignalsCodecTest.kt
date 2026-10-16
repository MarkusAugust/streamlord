package io.github.markusaugust.streamlord.json.jackson2

import io.github.markusaugust.streamlord.core.SignalsCodecException
import io.github.markusaugust.streamlord.core.SignalsDecodeException
import io.github.markusaugust.streamlord.core.port.driven.decode
import io.github.markusaugust.streamlord.core.port.driven.encode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class Jackson2SignalsCodecTest {
    data class Signals(
        val search: String = "",
        val count: Int = 0,
        val gone: String? = null,
    )

    private val codec = Jackson2SignalsCodec.default()

    @Test
    fun `encodes with nulls`() {
        assertEquals("""{"search":"ash","count":0,"gone":null}""", codec.encode(Signals(search = "ash")))
    }

    @Test
    fun `decodes data classes through the kotlin module`() {
        assertEquals(Signals("x", 2), codec.decode<Signals>("""{"search":"x","count":2}"""))
        assertEquals(mapOf("a" to 1), codec.decode<Map<String, Int>>("""{"a":1}"""))
    }

    interface Shape

    // No request can make an interface buildable: the server's to fix, so not a decode failure.
    @Test
    fun `a type that cannot be built is the server's`() {
        val failure = assertFailsWith<SignalsCodecException> { codec.decode<Shape>("""{"sides":3}""") }
        assertFalse(failure is SignalsDecodeException, "$failure")
    }

    @Test
    fun `wraps failures`() {
        assertFailsWith<SignalsDecodeException> { codec.decode<Signals>("""{"count":"no"}""") }
        assertFailsWith<SignalsDecodeException> { codec.decode<Signals>("not json") }
    }

    // The browser sends its whole store; the type names only what one handler accepts.
    @Test
    fun `ignores the signals the type does not name`() {
        assertEquals(Signals("x", 2), codec.decode<Signals>("""{"search":"x","count":2,"elsewhere":true}"""))
    }
}
