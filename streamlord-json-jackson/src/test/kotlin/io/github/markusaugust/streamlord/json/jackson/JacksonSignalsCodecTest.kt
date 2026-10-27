package io.github.markusaugust.streamlord.json.jackson

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.github.markusaugust.streamlord.core.SignalsCodecException
import io.github.markusaugust.streamlord.core.SignalsDecodeException
import io.github.markusaugust.streamlord.core.port.driven.decode
import io.github.markusaugust.streamlord.core.port.driven.encode
import io.github.markusaugust.streamlord.core.port.driven.toJson
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class JacksonSignalsCodecTest {
    data class Signals(
        val search: String = "",
        val count: Int = 0,
        val gone: String? = null,
    )

    private val codec = JacksonSignalsCodec.default()

    data class Form(
        val email: String = "",
        @param:JsonProperty("first-name") @get:JsonProperty("first-name") val firstName: String = "",
    )

    // A page declares the names the handler's readSignals<Form>() reads.
    @Test
    fun `toJson names the fields as decode reads them`() {
        val json = codec.toJson(Form(email = "a@b"))
        assertEquals("""{"email":"a@b","first-name":""}""", json.toJson())
        assertEquals(Form(email = "a@b"), codec.decode<Form>(json.toJson()))
    }

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
            JacksonSignalsCodec(
                JsonMapper
                    .builder()
                    .addModule(
                        KotlinModule.Builder().build(),
                    ).disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                    .build(),
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
        val bare = JacksonSignalsCodec(JsonMapper.builder().build())
        for (body in listOf("""{"page":3}""", "{}")) {
            val failure = assertFailsWith<SignalsCodecException> { bare.decode<Page>(body) }
            assertFalse(failure is SignalsDecodeException, "$failure")
        }
        assertFailsWith<SignalsCodecException> { bare.decode<List<Delete>>("""[{"id":1}]""") }
        assertEquals(mapOf("a" to 1), bare.decode<Map<String, Int>>("""{"a":1}"""))
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "t")
    @JsonSubTypes(JsonSubTypes.Type(value = Box::class, name = "box"))
    sealed interface Shaped

    data class Box(
        val label: String = "",
        val page: Int = 1,
    ) : Shaped

    // The interface names no class Jackson builds, but its subtypes are Kotlin and lose their values the same way.
    @Test
    fun `a Kotlin interface needs the Kotlin module too`() {
        assertFailsWith<SignalsCodecException> {
            JacksonSignalsCodec(JsonMapper.builder().build()).decode<Shaped>("""{"t":"box","label":"x","page":3}""")
        }
        assertEquals(Box("x", 3), codec.decode<Shaped>("""{"t":"box","label":"x","page":3}"""))
    }

    // A Java creator keeps the mapper's rule, under which a missing component that takes null is null.
    @Test
    fun `a Java record may leave out a component that takes null`() {
        for (c in listOf(codec, JacksonSignalsCodec(JsonMapper.builder().build()))) {
            assertEquals(JavaSignals("a", null, 1), c.decode<JavaSignals>("""{"name":"a","n":1}"""))
            assertFailsWith<SignalsDecodeException> { c.decode<JavaSignals>("""{"name":"a","n":null}""") }
            // Unlike Jackson 2, which refuses it; the table on the Signals page has both.
            assertEquals(JavaSignals("a", null, 0), c.decode<JavaSignals>("""{"name":"a"}"""))
        }
    }

    @Test
    fun `ignores the signals the type does not name with a mapper that refuses them`() {
        val strict =
            JsonMapper
                .builder()
                .addModule(KotlinModule.Builder().build())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build()
        assertEquals(Page(2), JacksonSignalsCodec(strict).decode<Page>("""{"page":2,"other":1}"""))
    }

    data class Held(
        val xs: List<Int> = emptyList(),
        val m: Map<String, Int> = emptyMap(),
        val deep: Map<String, List<List<Int>>> = emptyMap(),
    )

    @Test
    fun `a null inside a collection that holds none is refused`() {
        for (body in listOf("""{"xs":[1,null]}""", """{"m":{"a":null}}""", """{"deep":{"a":[[1],[2,null]]}}""")) {
            assertFailsWith<SignalsDecodeException>(body) { codec.decode<Held>(body) }
        }
        // The Kotlin module checks a constructor's parameters; a map read whole is checked by the codec.
        assertFailsWith<SignalsDecodeException> { codec.decode<Map<String, Int>>("""{"a":null}""") }
        assertEquals(mapOf("a" to null), codec.decode<Map<String, Int?>>("""{"a":null}"""))
    }

    // A Java collection takes null; its platform type only looks as if it did not.
    @Test
    fun `a Java record keeps null inside its collections`() {
        for (c in listOf(codec, JacksonSignalsCodec(JsonMapper.builder().build()))) {
            assertEquals(
                JavaHeld(listOf(1, null), mapOf("a" to null)),
                c.decode<JavaHeld>("""{"xs":[1,null],"m":{"a":null}}"""),
            )
        }
    }

    @Test
    fun `a null inside a collection read whole is refused at any depth`() {
        for (c in listOf(codec, JacksonSignalsCodec(JsonMapper.builder().build()))) {
            assertFailsWith<SignalsDecodeException> { c.decode<Array<String>>("""["a",null]""") }
            assertEquals(listOf("a", null), c.decode<Array<String?>>("""["a",null]""").toList())
            assertFailsWith<SignalsDecodeException> { c.decode<List<Int>>("""[1,null]""") }
            assertFailsWith<SignalsDecodeException> { c.decode<Set<String>>("""["a",null]""") }
            assertFailsWith<SignalsDecodeException> { c.decode<Map<String, List<Int>>>("""{"a":[1,null]}""") }
            assertFailsWith<SignalsDecodeException> { c.decode<List<List<Int>>>("""[[1],[2,null]]""") }
            assertFailsWith<SignalsDecodeException> { c.decode<Map<String, Map<String, Int>>>("""{"a":{"b":null}}""") }
            assertEquals(listOf(listOf(1, null)), c.decode<List<List<Int?>>>("""[[1,null]]"""))
            assertEquals(mapOf("a" to null), c.decode<Map<String, List<Int>?>>("""{"a":null}"""))
        }
    }

    enum class Colour { RED, GREEN }

    data class Painted(
        val c: Colour,
    )

    // Unlike kotlinx, which refuses both; the table on the Signals page has it.
    @Test
    fun `an enum is read by position as well as by name`() {
        for (body in listOf("""{"c":0}""", """{"c":"0"}""")) {
            assertEquals(Painted(Colour.RED), codec.decode<Painted>(body), body)
        }
    }
}
