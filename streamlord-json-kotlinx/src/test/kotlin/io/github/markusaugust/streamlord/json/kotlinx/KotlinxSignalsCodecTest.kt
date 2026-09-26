package io.github.markusaugust.streamlord.json.kotlinx

import io.github.markusaugust.streamlord.core.SignalsCodecException
import io.github.markusaugust.streamlord.core.port.driven.decode
import io.github.markusaugust.streamlord.core.port.driven.encode
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KotlinxSignalsCodecTest {

    @Serializable
    data class Signals(val search: String = "", val count: Int = 0, val gone: String? = null)

    private val codec = KotlinxSignalsCodec()

    @Test
    fun `encodes defaults and explicit nulls`() {
        assertEquals("""{"search":"ash","count":0,"gone":null}""", codec.encode(Signals(search = "ash")))
    }

    @Test
    fun `decodes and ignores unknown keys`() {
        assertEquals(Signals("x", 2), codec.decode<Signals>("""{"search":"x","count":2,"_private":true}"""))
    }

    @Test
    fun `wraps failures`() {
        assertFailsWith<SignalsCodecException> { codec.decode<Signals>("""{"count":"not a number"}""") }
        assertFailsWith<SignalsCodecException> { codec.decode<Signals>("not json") }
    }
}
