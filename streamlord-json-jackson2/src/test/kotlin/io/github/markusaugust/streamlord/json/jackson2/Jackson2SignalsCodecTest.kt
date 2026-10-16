package io.github.markusaugust.streamlord.json.jackson2

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
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

    data class Delete(
        val id: Long,
    )

    data class Page(
        val page: Int = 1,
    )

    data class Find(
        val q: String?,
    )

    // A required signal the browser did not send must not reach the handler as 0.
    @Test
    fun `a missing or null primitive is refused, whatever the mapper says`() {
        val lenient =
            Jackson2SignalsCodec(
                ObjectMapper()
                    .registerModule(
                        KotlinModule.Builder().build(),
                    ).configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false),
            )
        for (c in listOf(codec, lenient)) {
            assertFailsWith<SignalsDecodeException> { c.decode<Delete>("{}") }
            assertFailsWith<SignalsDecodeException> { c.decode<Delete>("""{"id":null}""") }
            assertFailsWith<SignalsDecodeException> { c.decode<Page>("""{"page":null}""") }
            assertEquals(Page(), c.decode<Page>("{}"))
            // Unlike kotlinx, the Kotlin module reads a nullable parameter as optional.
            assertEquals(Find(null), c.decode<Find>("{}"))
        }
    }

    @Test
    fun `anything after the value is refused`() {
        assertFailsWith<SignalsDecodeException> { codec.decode<Page>("""{"page":2} {"page":3}""") }
    }

    // Without the module Jackson reads past the constructor: values dropped, null in a String, or a 400 that is the server's.
    @Test
    fun `a Kotlin class needs the Kotlin module on the mapper`() {
        val bare = Jackson2SignalsCodec(ObjectMapper())
        for (body in listOf("""{"page":3}""", "{}")) {
            val failure = assertFailsWith<SignalsCodecException> { bare.decode<Page>(body) }
            assertFalse(failure is SignalsDecodeException, "$failure")
        }
        assertFailsWith<SignalsCodecException> { bare.decode<List<Delete>>("""[{"id":1}]""") }
        assertEquals(mapOf("a" to 1), bare.decode<Map<String, Int>>("""{"a":1}"""))
    }
}
