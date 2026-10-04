package io.github.markusaugust.streamlord.json.kotlinx

import io.github.markusaugust.streamlord.core.SignalsCodecException
import io.github.markusaugust.streamlord.core.port.driven.decode
import io.github.markusaugust.streamlord.core.port.driven.encode
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KotlinxSignalsCodecTest {
    @Serializable
    data class Signals(
        val search: String = "",
        val count: Int = 0,
        val gone: String? = null,
    )

    private val codec = KotlinxSignalsCodec()

    @Test
    fun `encodes defaults and explicit nulls`() {
        assertEquals("""{"search":"ash","count":0,"gone":null}""", codec.encode(Signals(search = "ash")))
    }

    @Test
    fun `decodes and ignores unknown keys`() {
        assertEquals(Signals("x", 2), codec.decode<Signals>("""{"search":"x","count":2,"_private":true}"""))
    }

    /*
     * The reason the parameter exists is GraalVM, where the reflective lookup finds nothing, and
     * that cannot be asserted from a JVM test. What can: that a named serializer is the one used,
     * which is the whole mechanism. If this passes, the native image is using the generated
     * serializer rather than reading the class.
     */
    @Test
    fun `a named serializer is used instead of the reflective lookup`() {
        val named = KotlinxSignalsCodec(serializers = mapOf(typeOf<Signals>() to ShoutingSignals))

        assertEquals("""{"search":"ASH"}""", named.encode(Signals(search = "ash")))
        assertEquals(Signals(search = "ash"), named.decode<Signals>("""{"search":"ASH"}"""))
    }

    @Test
    fun `types that are not named still resolve themselves`() {
        val named = KotlinxSignalsCodec(serializers = mapOf(typeOf<Signals>() to ShoutingSignals))

        assertEquals("""{"name":"gorvek"}""", named.encode(Other("gorvek")))
    }

    /*
     * Requiring named serializers turns the one failure this module can hide into the one it cannot: a type
     * that was never named resolves reflectively on a JVM and fails inside a native image, so
     * a codec that refuses the fallback moves the error to where a test can see it.
     */
    @Test
    fun `naming required refuses a type nobody named`() {
        val codec = KotlinxSignalsCodec(serializers = mapOf(typeOf<Signals>() to ShoutingSignals), requireNamedSerializers = true)

        assertEquals("""{"search":"ASH"}""", codec.encode(Signals(search = "ash")))

        val refused = assertFailsWith<SignalsCodecException> { codec.encode(Other("gorvek")) }
        assertTrue(refused.message!!.contains("Other"), "the message names the type that is missing")
        assertTrue(refused.message!!.contains("native image"), "and says why it matters")
    }

    @Test
    fun `naming required lets through what a native image can resolve on its own`() {
        val codec = KotlinxSignalsCodec(serializers = mapOf(typeOf<Signals>() to ShoutingSignals), requireNamedSerializers = true)

        // Answered from kotlinx's own table, not by reading a class: safe in an image, so
        // refusing them would be refusing something that works.
        assertEquals(""""ash"""", codec.encode("ash"))
        assertEquals("""["ash","vow"]""", codec.encode(listOf("ash", "vow")))
        assertEquals("""{"heads":13}""", codec.encode(mapOf("heads" to 13)))
    }

    @Test
    fun `naming required follows a type into what it contains`() {
        val codec = KotlinxSignalsCodec(serializers = mapOf(typeOf<Signals>() to ShoutingSignals), requireNamedSerializers = true)

        // A list of a class nobody named fails for the same reason the class would.
        val refused = assertFailsWith<SignalsCodecException> { codec.encode(listOf(Other("gorvek"))) }
        assertTrue(refused.message!!.contains("Other"))
    }

    @Test
    fun `wraps failures`() {
        assertFailsWith<SignalsCodecException> { codec.decode<Signals>("""{"count":"not a number"}""") }
        assertFailsWith<SignalsCodecException> { codec.decode<Signals>("not json") }
    }

    @Serializable
    data class Other(
        val name: String,
    )

    /** Writes the search in capitals, so the test can tell which serializer ran. */
    private object ShoutingSignals : KSerializer<Signals> {
        override val descriptor: SerialDescriptor =
            buildClassSerialDescriptor("Shouting") {
                element<String>("search")
            }

        override fun serialize(
            encoder: Encoder,
            value: Signals,
        ) {
            encoder.encodeStructure(descriptor) {
                encodeStringElement(descriptor, 0, value.search.uppercase())
            }
        }

        override fun deserialize(decoder: Decoder): Signals =
            decoder.decodeStructure(descriptor) {
                var search = ""
                while (true) {
                    when (val index = decodeElementIndex(descriptor)) {
                        0 -> search = decodeStringElement(descriptor, 0).lowercase()
                        CompositeDecoder.DECODE_DONE -> break
                        else -> error("Unexpected index $index")
                    }
                }
                Signals(search = search)
            }
    }

    // A body that does not fit the type is not a missing serializer, and must not say it is.
    @Test
    fun `a body of the wrong shape does not blame the serializers`() {
        val e = assertFailsWith<SignalsCodecException> { KotlinxSignalsCodec().decode<Map<String, Int>>("""{"a":"no"}""") }
        assertFalse("serializer" in e.message.orEmpty().lowercase(), e.message)
    }
}
