package io.github.markusaugust.streamlord.json.kotlinx

import io.github.markusaugust.streamlord.core.SignalsCodecException
import io.github.markusaugust.streamlord.core.SignalsDecodeException
import io.github.markusaugust.streamlord.core.port.driven.decode
import io.github.markusaugust.streamlord.core.port.driven.encode
import io.github.markusaugust.streamlord.core.port.driven.toJsonValue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.json.JsonElement
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

    @Serializable
    data class Form(
        val email: String = "",
        @SerialName("first-name") val firstName: String = "",
    )

    // A page declares the names the handler's readSignals<Form>() reads.
    @Test
    fun `toJsonValue names the fields as decode reads them`() {
        val json = codec.toJsonValue(Form(email = "a@b"))
        assertEquals("""{"email":"a@b","first-name":""}""", json.toJson())
        assertEquals(Form(email = "a@b"), codec.decode<Form>(json.toJson()))
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
        assertFalse(refused is SignalsDecodeException, "a missing serializer is the server's, not the request's")
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

    // The list is looked up whole, and that lookup reads the class of what it holds, never the map.
    @Test
    fun `naming required refuses a container of a named type until the container is named`() {
        val codec = KotlinxSignalsCodec(serializers = mapOf(typeOf<Signals>() to ShoutingSignals), requireNamedSerializers = true)
        for (refused in listOf(
            assertFailsWith<SignalsCodecException> { codec.decode<List<Signals>>("""[{"search":"ASH"}]""") },
            assertFailsWith<SignalsCodecException> { codec.decode<Map<String, Signals>>("""{"a":{"search":"ASH"}}""") },
            assertFailsWith<SignalsCodecException> { codec.decode<Map<String, List<Signals>>>("""{"a":[]}""") },
        )) {
            assertTrue(refused.message!!.contains("No serializer was named"), refused.message)
        }

        val named =
            KotlinxSignalsCodec(
                serializers = mapOf(typeOf<List<Signals>>() to ListSerializer(ShoutingSignals)),
                requireNamedSerializers = true,
            )
        assertEquals(listOf(Signals(search = "ash")), named.decode<List<Signals>>("""[{"search":"ASH"}]"""))
    }

    @Serializable
    data class Loose(
        val page: Int = 1,
        val extra: JsonElement? = null,
    )

    // A JsonElement is read by recursion; a caller that skips Streamlord.readSignals still gets a 400.
    @Test
    fun `nesting too deep for the stack is a decode failure`() {
        val depth = 200_000
        val body = """{"page":2,"extra":""" + "[".repeat(depth) + "]".repeat(depth) + "}"
        assertFailsWith<SignalsDecodeException> { codec.decode<Loose>(body) }
        assertEquals(2, codec.decode<Loose>("""{"page":2,"extra":[[[]]]}""").page)
    }

    @Test
    fun `wraps failures`() {
        assertFailsWith<SignalsDecodeException> { codec.decode<Signals>("""{"count":"not a number"}""") }
        assertFailsWith<SignalsDecodeException> { codec.decode<Signals>("not json") }
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

    @Serializable
    enum class Colour { RED, GREEN }

    @Serializable
    data class Painted(
        val c: Colour,
    )

    // Jackson reads a number as the constant at that position; the table on the Signals page has both.
    @Test
    fun `an enum is read by name only`() {
        assertEquals(Painted(Colour.GREEN), codec.decode<Painted>("""{"c":"GREEN"}"""))
        for (body in listOf("""{"c":0}""", """{"c":"0"}""")) {
            assertFailsWith<SignalsDecodeException>(body) { codec.decode<Painted>(body) }
        }
    }

    @Serializable
    data class Numbered(
        val xs: Array<Int>,
    )

    // kotlinx.serialization builds an int[] for it and fails to cast it, on every body.
    @Test
    fun `an Array of a primitive looked up by type is the server's, with the fix in the message`() {
        for (body in listOf("[1,2]", "[1,null]", "nope")) {
            val failure = assertFailsWith<SignalsCodecException>(body) { codec.decode<Array<Int>>(body) }
            assertFalse(failure is SignalsDecodeException, "$failure")
            assertTrue("IntArray" in failure.message.orEmpty(), failure.message)
        }
        assertFailsWith<SignalsCodecException> { codec.decode<List<Array<Long>>>("[[1]]") }
        assertEquals(listOf(1, 2), codec.decode<IntArray>("[1,2]").toList())
        assertEquals(listOf(1, null), codec.decode<Array<Int?>>("[1,null]").toList())
        assertEquals(listOf("a"), codec.decode<Array<String>>("""["a"]""").toList())
        // The compiler plugin's serializer for a property is right.
        assertEquals(listOf(1, 2), codec.decode<Numbered>("""{"xs":[1,2]}""").xs.toList())
    }
}
