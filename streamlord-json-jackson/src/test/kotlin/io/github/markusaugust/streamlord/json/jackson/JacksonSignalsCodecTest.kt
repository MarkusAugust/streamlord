package io.github.markusaugust.streamlord.json.jackson

import io.github.markusaugust.streamlord.core.SignalsCodecException
import io.github.markusaugust.streamlord.core.port.driven.decode
import io.github.markusaugust.streamlord.core.port.driven.encode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JacksonSignalsCodecTest {

    data class Signals(val search: String = "", val count: Int = 0, val gone: String? = null)

    private val codec = JacksonSignalsCodec.default()

    @Test
    fun `encodes with nulls`() {
        assertEquals("""{"search":"ash","count":0,"gone":null}""", codec.encode(Signals(search = "ash")))
    }

    @Test
    fun `decodes data classes through the kotlin module`() {
        assertEquals(Signals("x", 2), codec.decode<Signals>("""{"search":"x","count":2}"""))
        assertEquals(mapOf("a" to 1), codec.decode<Map<String, Int>>("""{"a":1}"""))
    }

    @Test
    fun `wraps failures`() {
        assertFailsWith<SignalsCodecException> { codec.decode<Signals>("""{"count":"no"}""") }
    }
}
